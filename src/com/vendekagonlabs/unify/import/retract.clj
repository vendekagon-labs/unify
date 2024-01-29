(ns com.vendekagonlabs.unify.import.retract
  (:require [com.vendekagonlabs.unify.db.schema :as schema]
            [com.vendekagonlabs.unify.db :as db]))


(defn schema->kind-ids
  [schema]
  (->> schema
       (first)  ; index by kinds is first pos of schema
       (:index/kinds)
       (remove (fn [[_ ent]]
                 (:unify.kind/ref-data ent)))
       (map (fn [[_ ent]]
              (or (get-in ent [:unify.kind/need-uid :db/ident])
                  (get-in ent [:unify.kind/global-id :db/ident]))))
       (remove :dataset/name)))

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

(defn dataset->entity-ids
  [db dataset-name]
  ;; TODO: `first` logic will have to change after diff merge, maybe just
  ;;       throw and rule out retract in that case?
  (let [import (first (dataset->imports db dataset-name))
        schema (schema/get-metamodel-and-schema db)
        txes (import->txes db import)
        start-tx (reduce min txes)
        stop-tx (inc (reduce max txes))]))
    ;; and then filter tx-range to start and tx positions
    ;; then scan over and find all assertions of unique kind ids
    ;; then batch transactions of N size of retractEntity tx fn call

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
  (:data (first tx-seq)))

(comment
  :schema-info
  (def schema (schema/get-metamodel-and-schema db))
  (schema->kind-ids schema))
