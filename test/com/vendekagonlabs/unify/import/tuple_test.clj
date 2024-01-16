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
(ns com.vendekagonlabs.unify.import.tuple-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [com.vendekagonlabs.unify.bootstrap.data :as bootstrap.data]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [com.vendekagonlabs.unify.test-util :as tu]
            [com.vendekagonlabs.unify.db :as db]))

(def tmp-dir "tuple-tmp-output")
(def datomic-uri (str "datomic:mem://tuple-tests"))
(def import-cfg-file "test/resources/systems/candel/tuple-import/config.edn")
(def schema-dir "test/resources/systems/candel/template-dataset/schema")

(defn setup []
  (log/info "Initializing in-memory tuple test db.")
  (db/init datomic-uri
           :schema-directory schema-dir)

  (log/info "Bootstrap gene/HGNC data only.")
  (let [conn (d/connect datomic-uri)
        genes-data (tu/bootstrap-genes)]
    (doseq [f (:files genes-data)]
      (db/transact-bootstrap-data
        conn
        (io/file "test/resources/systems/candel/reference-data" f)))))

(defn teardown []
  (log/info "Ending tuple test and deleting db.")
  (d/delete-database datomic-uri)
  (util.io/delete-recursively tmp-dir))

(deftest tuple-import
  (setup)
  (is (:results (tu/run-import
                  {:target-dir           tmp-dir
                   :datomic-uri          datomic-uri
                   :import-cfg-file      import-cfg-file
                   :schema-directory     schema-dir
                   :disable-remote-calls true
                   :tx-batch-size        50})))
  (teardown))


(comment
  (run-tests *ns*))