(ns com.vendekagonlabs.unify.import.retract
  (:require [com.vendekagonlabs.unify.db.schema :as schema]
            [com.vendekagonlabs.unify.db :as db]
            [com.vendekagonlabs.unify.db.transact :as transact]
            [datomic.api :as d]))


(defn schema->kind-ids
  "Given a schema, returns all id attributes for every kind defined in
  the schema.

  _Note_: it may make sense to move this into metamodel namespace, if
  it proves to be generally useful."
  [schema]
  (->> schema
       (first)  ; index by kinds is first pos of schema
       (:index/kinds)
       ;; for now, let's retract reference data from import,
       ;; it's not yet clear to me if reference data should be exempt
       ;; from dataset retraction, as use in CANDEL drifted from its
       ;; original design here. Most likely, this should be a
       ;; user option.
       #_(remove (fn [[_ ent]]
                   (:unify.kind/ref-data ent)))
       (map (fn [[_ ent]]
              (or (get-in ent [:unify.kind/need-uid :db/ident])
                  (get-in ent [:unify.kind/global-id :db/ident]))))
       (remove :dataset/name)))

(defn dataset->imports
  "Given a `db` and the name of a dataset, finds all imports
  that created or modified that dataset, and are currently
  extant (i.e. not retracted) in the present db view."
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
  "Given an import, returns all transaction entities that were
  part of that import. Note, this behavior depends on Unify
  tx annotations and cannot be applied outside Unify managed batch
  imports."
  [db import-name]
  (map first
       (d/q '[:find ?tx
              :in $ ?import-name
              :where
              [?i :unify.import/name ?import-name]
              [?tx :unify.import.tx/import ?i]]
            db import-name)))

(defn filter-tx-datoms
  "Given a db, `set` of attrs, and tx-info as per each element returned
  from datomic.api/tx-range, returns only those datoms from the tx that
  refer to attributes in the attr-set."
  [db attr-set {:keys [data] :as _tx-info}]
  (let [attr-vals (mapv (fn [[_e a v]]
                          [(:db/ident (d/pull db '[:db/ident] a))
                           v])
                        data)]
    (filterv (fn [[a]]
               (attr-set a))
             attr-vals)))

(defn dataset->retractions
  "Given a db and log (both from a datomic.api/conn) as well as a dataset-name,
  returns all unify kind entity ids asserted as part of the import."
  [db log dataset-name]
  ;; TODO: `first` logic will have to change after diff merge, maybe just
  ;;       throw and rule out retract in that case?
  (let [import (last (dataset->imports db dataset-name))
        schema (schema/get-metamodel-and-schema db)
        kind-ids (set (schema->kind-ids schema))
        txes (import->txes db import)
        start-tx (reduce min txes)
        stop-tx (inc (reduce max txes))
        tx-seq (d/tx-range log start-tx stop-tx)]
    (->> tx-seq
         (mapcat (partial filter-tx-datoms db (set kind-ids)))
         (map (fn [lookup-ref]
                [:db.fn/retractEntity lookup-ref]))
         (partition-all 100))))

(defn retract-dataset
  [db-info dataset-name]
  (let [conn (db/get-connection db-info)
        log (d/log conn)
        db (d/db conn)
        txes (dataset->retractions db log dataset-name)
        annotated-txes (map (fn [tx-batch]
                              (concat [:db/add :db.part/tx
                                       :unify.import.tx/id (str (random-uuid))]
                                      tx-batch))
                            txes)]
    (transact/async-transact-w-retry conn annotated-txes {:skip-annotations true})))

(comment
  :db-setup
  (def db-name "retract-test")
  (def db-info (db/fetch-info db-name))
  (def db (db/latest-db db-info))
  (def conn (db/get-connection db-info)))

(comment
  :retract-test
  (retract-dataset db-info "matrix-test")

  ;; :queries
  (require '[datomic.api :as d])
  (d/q '[:find ?dname
         :where
         [?d :dataset/name ?dname]]
       db)

  (def retractions
    (dataset->retractions db (d/log conn) "matrix-test"))
  (doseq [tx retractions]
    @(d/transact conn tx))
  (def db2 (d/db conn))

  (d/q '[:find ?assay :in $
         :where
         [?d :dataset/name "matrix-test"]
         [?d :dataset/assays ?a]
         [?a :assay/name ?assay]]
       (d/history db2)))


(comment
  :schema-info
  (def schema (schema/get-metamodel-and-schema db))
  (def kind-ids (schema->kind-ids schema)))
