(ns com.vendekagonlabs.unify.import.retract
  (:require [com.vendekagonlabs.unify.db.schema :as schema]
            [com.vendekagonlabs.unify.db :as db]))

(defn schema [db]
  (schema/get-metamodel-and-schema db))


(defn kinds->ids [])

(defn dataset->imports
  [db dataset-name]
  (map first
       (d/q '[:find ?import-name
              :in $ ?dataset-name
              :where
              [?d :dataset/name ?dataset-name]
              [?ui :unify.import/name ?import-name]
              [?ui :unify.import/dataset ?d]]
            db dataset-name)))

(defn import->txes
  [db import-name]
  (map first
       (d/q '[:find ?tx
              :in $ ?import-name
              :where
              [?i :unify.import/name ?import-name]
              [?tx :unify.import.tx/import ?i]]
            db import-name)))

(defn dataset->entities
  [db dataset-name])

(comment
  :db-setup
  (def db-name "retract-test")
  (def db-info (db/fetch-info db-name))
  (def db (db/latest-db db-info))
  (def conn (db/get-connection db-info)))

(comment
  :queries
  (require '[datomic.api :as d])
  (def imports
    (dataset->imports db "matrix-test"))

  (def txes (import->txes db (first imports)))
  (def lower-tx (reduce min txes))
  (def upper-tx (reduce max txes))
  (def tx-seq (d/tx-range (d/log conn) lower-tx (inc upper-tx)))
  (:data (first tx-seq))

  (d/q '[:find ?dname
         :in $
         :where
         [?d :dataset/name ?dname]]
       db))

(comment
  :schema-info
  (def schema (schema/get-metamodel-and-schema db))
  (def index (first schema))
  (keys index)
  (def kinds-best-id
    (map (fn [[_ ent]]
           (or (get-in ent [:unify.kind/need-uid :db/ident])
               (get-in ent [:unify.kind/global-id :db/ident])))
         (:index/kinds index))))
