(ns com.vendekagonlabs.unify.import.retract
  (:require [com.vendekagonlabs.unify.db.schema :as schema]
            [com.vendekagonlabs.unify.db :as db]
            [clojure.core.async :as a]
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
  (d/q '[:find ?import-name ?tx
         :in $ ?dataset-name
         :where
         [?d :dataset/name ?dataset-name]
         [?ui :unify.import/name ?import-name ?tx]
         [?ui :unify.import/dataset ?d]]
       db dataset-name))

(defn retractable?
  "Given a db and dataset name, determines whether or
  not the dataset in question is in a valid state to be retracted.

  _Note_: at present, only checks to see if dataset exists. In
  future, might be extended to diff'd datasets, TBD."
  [db dataset]
  (seq (d/q '[:find ?d
              :in $ ?dataset
              :where
              [?d :dataset/name ?dataset]]
            db dataset)))

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
  (let [imports (dataset->imports db dataset-name)
        latest-import (first (apply max-key second imports))
        schema (schema/get-metamodel-and-schema db)
        kind-ids (set (schema->kind-ids schema))
        txes (import->txes db latest-import)
        start-tx (reduce min txes)
        stop-tx (inc (reduce max txes))
        tx-seq (d/tx-range log start-tx stop-tx)]
    (->> tx-seq
         (mapcat (partial filter-tx-datoms db (set kind-ids)))
         (map (fn [lookup-ref]
                [:db.fn/retractEntity lookup-ref]))
         (partition-all 100))))

(defn pipeline-retractions!
  [db-info tx-data-batch]
  (let [conn (db/get-connection db-info)
        src-ch (a/chan 100)
        to-ch (a/chan 100)
        done-ch (a/chan)]
    ;; load tx seq onto chan
    (a/go
      (a/onto-chan! src-ch tx-data-batch))
    ;; print '.' as txes progress
    (a/go-loop [total 0]
      (when (zero? (mod total 10))
        (print ".") (flush))
      (if-let [c (a/<! to-ch)]
        (recur (inc total))
        (a/>! done-ch {:completed total})))
    ;; pipeline txes from src collection through print go-loop via to-ch,
    ;; return done-ch which indicates completion with a blocking take
    (a/pipeline-blocking 1
                         to-ch
                         (comp
                           (map (fn [tx-data]
                                  (prn tx-data)
                                  tx-data))
                           (map (fn [tx-data]
                                  (transact/async-transact-w-retry conn tx-data
                                                                   {:skip-annotations true}))))
                         src-ch)
    {:result done-ch
     :stop   (fn [] (a/close! to-ch))}))


(defn retract-dataset
  [db-info dataset-name]
  (let [conn (db/get-connection db-info)
        log (d/log conn)
        db (d/db conn)
        _ (when-not (retractable? db dataset-name)
            (throw (ex-info (str "Could not retract dataset: " dataset-name
                                 " -- does this dataset exist?")
                            {:retract/invalid-dataset-name dataset-name})))
        txes (dataset->retractions db log dataset-name)
        annotated-txes (map (fn [tx-batch]
                              (conj tx-batch
                                    [:db/add :db.part/tx
                                     :unify.import.tx/id (str (random-uuid))]))
                            txes)
        pipeline-result (pipeline-retractions! db-info annotated-txes)]
    (when-let [result (a/<!! (:result pipeline-result))]
      result)))

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
       (db/latest-db db-info))

  (def retractions
    (dataset->retractions db (d/log conn) "matrix-test"))
  (doseq [tx retractions]
    @(d/transact conn tx))
  (def db2 (d/db conn))

  (retractable? db "matrix-test")

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
