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
(ns com.vendekagonlabs.unify.import-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [com.vendekagonlabs.unify.util.text :as text]
            [com.vendekagonlabs.unify.test-util :as tu]
            [com.vendekagonlabs.unify.db :as db]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [com.vendekagonlabs.unify.validation.post-import :as post-import]))


(def candel-dir "test/resources/systems/candel")
(def template-dir (str candel-dir "/template-dataset"))
(def schema-directory (str template-dir "/schema"))
(def seed-data-directory (str candel-dir "/reference-data"))
(def import-cfg-file (str template-dir "/config.edn"))
(def datomic-uri "datomic:mem://int-tests")
(def tmp-dir "tmp-output")


(def dataset-name
  (memoize
    (fn []
      (-> import-cfg-file
          (util.io/read-edn-file)
          (get-in [:dataset :name])))))


(defn setup []
  (log/info "Initializing in-memory integration test db.")
  (db/init datomic-uri
           :schema-directory schema-directory
           :seed-data-directory seed-data-directory))

(defn teardown []
  (log/info "Ending integration test and deleting db.")
  (d/delete-database datomic-uri))

(deftest ^:integration sanity-test
  (try
    (setup)
    (let [import-result (tu/run-import
                          {:target-dir           tmp-dir
                           :datomic-uri          datomic-uri
                           :import-cfg-file      import-cfg-file
                           :schema-directory     schema-directory
                           :disable-remote-calls true
                           :tx-batch-size        50})]
      (testing "Import runs to completion without throwing."
        (is import-result))
      ;; TODO: make this test for a set of known entities rather than transactions, as transactions
      ;        aren't part of the contract, but e.g. 50 samples should be imported by this import
      ;        would be a fair stipulation of the contract implied by a unify import.
      (testing "Right number of txes completed. This implicitly also tests for data import failures."
        (is (=  2600 (get-in import-result [:results :completed]))))
      (testing "No reference data import errors."
        (is (not (seq (:errors import-result)))))
      (println "NOTE: currently skipping validation tests.")
     #_(testing "Validation runs with expected failures (until test updated)."
         (Thread/sleep 2000)
         (let [dataset-name (dataset-name)
               db-info {:uri datomic-uri}]
           (is (not (seq (post-import/run-all-validations db-info dataset-name)))))))

    (catch Exception e
      (log/error "Test threw during import attempt "
                 :message (.getMessage e)
                 :ex-data (text/->pretty-string (ex-data e)))
      (throw e))
    (finally
      (try
        (teardown)
        (util.io/delete-recursively tmp-dir)
        (catch Exception e
          (log/error (.getMessage e)))))))


(comment
  ;; comment has template for getting queries on db fast, comment out
  ;; teardown call above in finally then after eval on run-tests this will
  ;; set you up for query
  (run-tests *ns*))
