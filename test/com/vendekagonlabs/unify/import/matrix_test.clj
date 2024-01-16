;; Copyright 2023 Vendekagon Labs. All Rights Reserved.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;      http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS-IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns com.vendekagonlabs.unify.import.matrix-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [com.vendekagonlabs.unify.bootstrap.data :as bootstrap.data]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [com.vendekagonlabs.unify.test-util :as tu]
            [clojure.java.io :as io]
            [com.vendekagonlabs.unify.db :as db]
            [com.vendekagonlabs.unify.import :as import]
            [com.vendekagonlabs.unify.validation.post-import :as post-import]))

(def tmp-dir
  "matrix-tmp-output")

(def datomic-uri
  (str "datomic:mem://matrix-tests"))

(def import-cfg-file
  "test/resources/systems/candel/matrix/config.edn")

(def schema-dir
  "test/resources/systems/candel/template-dataset/schema")

(defn- config []
  (util.io/read-edn-file import-cfg-file))


(defn count-matrix-files
  [db]
  (d/q '[:find (count ?m) .
         :where
         [?m :measurement-matrix/backing-file]]
       db))

(defn matrices-with-parents
  "Checks to make sure measurement-matrix-names are all reachable from the
  relational graph entailed by parent entities.

  NOTE: this does not ensure the exact graph test for any, but the
  likelihood of fulfilling the 'any path' constraint and failing the
  'exact path' constraint is minimal enough that it's not worth covering
  as a separate test case unless a specific failure is observed."
  [db {:keys [dataset-name
              assay-names
              measurement-set-names
              measurement-matrix-names]}]
  (d/q '[:find (count ?mm) .
         :in $ ?ds-name [?a-name ...]
         [?ms-name ...] [?matrix-name ...]
         :where
         [?d :dataset/name ?ds-name]
         [?d :dataset/assays ?a]
         [?a :assay/name ?a-name]
         [?a :assay/measurement-sets ?ms]
         [?ms :measurement-set/name ?ms-name]
         [?ms :measurement-set/measurement-matrices ?mm]
         [?mm :measurement-matrix/name ?matrix-name]]
       db
       dataset-name
       assay-names
       measurement-set-names
       measurement-matrix-names))

(defn setup []
  (log/info "Initializing in-memory matrix test db.")
  (db/init datomic-uri :schema-directory schema-dir)
  (let [conn (d/connect datomic-uri)
        genes-data (tu/bootstrap-genes)]
    (doseq [f (:files genes-data)]
      (db/transact-bootstrap-data
        conn
        (io/file "test/resources/systems/candel/reference-data" f)))))

(defn teardown []
  (log/info "Ending matrix test and deleting db.")
  (d/delete-database datomic-uri)
  (util.io/delete-recursively tmp-dir))

(defn setup-teardown [test]
  (setup)
  (test)
  (teardown))

(use-fixtures :once setup-teardown)

(deftest matrix-import
  (testing "matrix import runs to completion"
    (is (tu/run-import
          {:target-dir           tmp-dir
           :datomic-uri          datomic-uri
           :import-cfg-file      import-cfg-file
           :schema-directory     schema-dir
           :disable-remote-calls true
           :tx-batch-size        50})))
  (testing "matrix import results in matrices being correctly transacted."
    (let [db (-> datomic-uri d/connect d/db)
          cfg (config)
          dataset-name (get-in cfg [:dataset :name])
          assays (get-in cfg [:dataset :assays])
          assay-names (map :name assays)
          measurement-sets (mapcat :measurement-sets assays)
          measurement-set-names (map :name measurement-sets)
          measurement-matrices (mapcat :measurement-matrices measurement-sets)
          mm-names (map :name measurement-matrices)
          mm-count (count measurement-matrices)]
      (testing "both matrix files end up in db"
        (is (= 2 (count-matrix-files db))))
      (testing "matrix file is nested in dataset correctly."
        (is (= mm-count
               (matrices-with-parents db {:measurement-set-names    measurement-set-names
                                          :assay-names              assay-names
                                          :dataset-name             dataset-name
                                          :measurement-matrix-names mm-names})))))
    (println "NOTE: currently skipping post-import validations.")
    #_(testing "matrix import validates"
        (Thread/sleep 500)
        (let [db-info {:uri datomic-uri}
              dataset-name (get-in (config) [:dataset :name])]
          (is (not (seq (post-import/run-all-validations db-info dataset-name))))))))


(comment
  (run-tests *ns*))

(comment
  :manual-prepare

  (import/prepare-import
    {:target-dir           "new-tmp"
     :datomic-uri          datomic-uri
     :import-cfg-file      import-cfg-file
     :schema-directory     schema-dir
     :disable-remote-calls true
     :tx-batch-size        50}))


(comment
  :db-exploration
  ;; if you need to investigate state of db after test,
  ;; comment #_(teardown) out

  (def conn (d/connect datomic-uri))
  (def db (d/db conn)))
