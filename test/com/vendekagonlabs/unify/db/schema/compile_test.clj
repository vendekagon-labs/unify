(ns com.vendekagonlabs.unify.db.schema.compile_test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [datomic.api :as d]
            [com.vendekagonlabs.unify.db.schema.compile :as sut]
            [com.vendekagonlabs.unify.util.io :as util.io]))

(defn patient-unify-schema []
  (util.io/read-edn-file "test/resources/systems/patient-dashboard/schema/unify.edn"))

(defn temp-datomic-uri []
  (->> (random-uuid)
       (str)
       (take 13)
       (apply str)
       (str "datomic:mem://")))

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


(comment
  (run-tests *ns*))
