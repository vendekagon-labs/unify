(ns com.vendekagonlabs.unify.import.retract
  (:require [com.vendekagonlabs.unify.db.schema :as schema]
            [com.vendekagonlabs.unify.db :as db]))

(defn schema [db]
  (schema/get-metamodel-and-schema db))


(defn kinds->ids [])

(defn dataset->entities
  [db dataset-name])

(comment
  :db-setup
  (def db-name "unify-example")
  (def db-info (db/fetch-info db-name))
  (def db (db/latest-db db-info)))

(comment
  :queries
  (require '[datomic.api :as d])
  (d/q '[:find ?dname
         :in $
         :where
         [?d :dataset/name ?dname]]
       db)
  (d/q '[:find ?import-name (pull ?i [*])
         :in $
         :where
         [?i :import/name ?import-name]]
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
