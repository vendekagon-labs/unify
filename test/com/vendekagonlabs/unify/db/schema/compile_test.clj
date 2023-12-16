(ns com.vendekagonlabs.unify.db.schema.compile_test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [datomic.api :as d]
            [com.vendekagonlabs.unify.db.schema.compile :as sut]
            [com.vendekagonlabs.unify.cli :as unify.cli]
            [com.vendekagonlabs.unify.cli.error-handling :as error-handling]
            [com.vendekagonlabs.unify.db.config :as config]
            [com.vendekagonlabs.unify.bootstrap.data :as bootstrap.data]
            [com.vendekagonlabs.unify.import :as import]
            [com.vendekagonlabs.unify.util.io :as util.io]))


(def patient-unify-schema-file
  "test/resources/systems/patient-dashboard/schema/unify.edn")
(defn patient-unify-schema []
  (util.io/read-edn-file patient-unify-schema-file))

(defn temp-datomic-uri []
  (->> (random-uuid)
       (str)
       (take 13)
       (apply str)
       (str "datomic:mem://")))

(defn unify-cli [& args]
  ;; Hacks, working around some design shortcoming
  ;; TODO: BK 12/16/2023 can clean up once we have:
  ;;   - boostrap data arg for request-db task
  ;;   - better config/base-uri hook
  ;;   Solution will: call import/request-db instead, w/out bootstrap data.
  (with-redefs [bootstrap.data/open-datasets (fn [] [])
                config/base-uri (fn [] "datomic:mem://")
                error-handling/exit (fn [code msg]
                                      (println "Would have existed with code:" code
                                               "and message:" msg))]
    (apply unify.cli/-main args)))

(defn request-db [schema-dir db-name]
  (unify-cli "request-db"
             "--schema-directory" schema-dir
             "--database" db-name))

(defn delete-db [db-name]
  (unify-cli "delete-db" "--database" db-name))

(deftest compiled-schema-transact-test
  (let [db-uri (temp-datomic-uri)
        _ (d/create-database db-uri)
        conn (d/connect db-uri)
        _ @(d/transact conn (util.io/read-edn-file (io/resource "unify-schema.edn")))
        db (d/db conn)]
    (testing "Compiled schema can all transact into Datomic."
      (let [ex-unify-schema (patient-unify-schema)
            compiled-schema (sut/->raw-schema ex-unify-schema)]
        (testing "Datomic schema transacts."
          (is (:db-after @(d/transact conn (:datomic/schema compiled-schema)))))
        (testing "Enums transact."
          (is (:db-after @(d/transact conn (:unify/enums compiled-schema)))))
        (testing "Unify metamodel transacts."
          (is (:db-after @(d/transact conn (:unify/metamodel compiled-schema)))))))))

(defn- cleanup-temp-directory [dir-name]
  (try
    (util.io/delete-recursively dir-name)
    (catch Exception _e
      (println "WARN: could not delete temporary test output directory: " dir-name))))

(defn- exists? [f]
  (.exists (io/file f)))
(deftest schema-dir-tests
  (let [test-schema-dir "schema-compile-temp-test"
        schema-data (sut/->raw-schema (patient-unify-schema))]
    (testing "Can write files to schema directory."
      (sut/write-schema-dir! test-schema-dir schema-data))
    (testing "Expected schema dir files exist after write."
      (is (every? exists? [(io/file test-schema-dir "metamodel.edn")
                           (io/file test-schema-dir "schema.edn")
                           (io/file test-schema-dir "enums.edn")])))
    (testing "Can request-db using the compiled schema directory."
      (request-db test-schema-dir "temp-schema-compile-test")
      (Thread/sleep 100)
      (delete-db "temp-schema-compile-test"))
    (cleanup-temp-directory test-schema-dir)))
    ;; cleanup at end (move to fixture)

(deftest unify-import-internal-api-test
  (let [test-schema-dir "schema-compile-temp-test-2"]
    (testing "Import API entrypoint for schema compilation works (sanity test)"
      (is (= :success (:results (import/compile-schema
                                  {:schema-directory test-schema-dir
                                   :unify-schema patient-unify-schema-file})))))
    (cleanup-temp-directory test-schema-dir)))


(comment
  (run-tests *ns*))
