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
(ns com.vendekagonlabs.unify.import.upsert-coordination
  (:require [com.vendekagonlabs.unify.db.metamodel :as metamodel]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [com.vendekagonlabs.unify.db.schema :as db.schema]
            [clojure.edn :as edn]
            [com.vendekagonlabs.unify.db.query :as dq]))

(def ignored-entity-keys #{:import/import :db/id :import/tx-id :unify/annotations})

(def ent-q
  '[:find ?e ?i ?a ?v
    :in $ [[?a ?v ?i]]
    :where
    [?e ?a ?v]])

(defn query-for-existing-entities
  "Given a collection of attr/value pairs, query the database and return the entity Id for
  any pairs that currently exist in the db."
  [db av-pairs]
  (dq/q+retry ent-q db av-pairs))

(defn- unique-av-pair
  "Return the unique (identifying) attr/value pair for the given entity as a tuple
  in the original entity map. Returns nil for 'filtered' entities - those defined by
  ignored-entity-keys."
  [schema ent]
  (when-let [ent-keys (seq (remove ignored-entity-keys (keys ent)))]
    (when-let [unique-attr (first (filter (partial metamodel/unique? schema) ent-keys))]
      (let [unique-val (get ent unique-attr)]
        (assoc ent :unify/identifying-avpair [unique-attr unique-val])))))

(defn- remove-unify-keys
  [m]
  (into {} (remove #(= (namespace (key %)) "unify") m)))

(defn- replace-nested-map-with-eid
  [entity-map ref-attr]
  (update entity-map ref-attr #(get % :db/id)))

(defn- replace-idents-with-eids
  [db entity-map ref-attr]
  (update entity-map ref-attr
          #(:db/id (d/pull db '[:db/id] %))))

(defn check-single-entity-for-upsert
  "Given an entity ID and an entity map (from the tx data), compare all the attr/value
  pairs in the entity map to the existing entity in the DB. Return nil if not an
  altering upsert. If the two differ, return a map with both entities."
  [db schema tx-ent e-id]
  (let [filtered-ent (remove-unify-keys tx-ent)
        attr-names (into [] (keys filtered-ent))
        ref-attrs (filter #(metamodel/ref? schema %) attr-names)

        db-entity-vals (d/pull db attr-names e-id)
        resolved-db-ent (reduce replace-nested-map-with-eid db-entity-vals ref-attrs)
        db-ent-set (into #{} resolved-db-ent)

        resolved-tx-ent (reduce (partial replace-idents-with-eids db) filtered-ent ref-attrs)
        tx-ent-set (into #{} resolved-tx-ent)
        upsert-changes (into {} (clojure.set/difference tx-ent-set db-ent-set))]
    (when-not (empty? upsert-changes)
      {:database-entity db-entity-vals :transaction-entity tx-ent :difference upsert-changes})))

(defn check-tx-for-upserts
  "Query the DB for each unique-identity a/v pair in the transaction and return the collection
  of entities that include actual data-updating upserts, along with the entity maps of
  the current entities in the DB."
  [schema db tx]
  (let [av-pairs (keep (partial unique-av-pair schema) tx)
        indexed-av-pairs (map-indexed #(conj (:unify/identifying-avpair %2) %1) av-pairs)
        potentially-upserting (remove empty? (query-for-existing-entities db indexed-av-pairs))]
    (keep (fn [[ent-id i]]
            (let [tx-ent (nth av-pairs i)]
              (check-single-entity-for-upsert db schema tx-ent ent-id)))
          potentially-upserting)))

(defn report-upserts
  "Given an input file of tx-data and an ensured Datomic config map,
  report any entities that will upsert with updated data (i.e. not reasserting
  the same value(s)) if transacted against the database."
  [datomic-uri tx-data-file]
  (log/info "Checking for reference data conflicts with: " tx-data-file)
  (with-open [in (java.io.PushbackReader. (io/reader tx-data-file))]
    (let [conn (d/connect datomic-uri)
          db (d/db conn)
          schema (db.schema/get-metamodel-and-schema)
          tx-seq (->> (repeatedly #(edn/read {:eof ::eof} in))
                      (take-while #(not= % ::eof)))]
      (->> (mapcat (partial check-tx-for-upserts schema db) tx-seq)
           (into [])))))
