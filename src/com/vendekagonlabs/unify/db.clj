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
(ns com.vendekagonlabs.unify.db
  (:require [com.vendekagonlabs.unify.import.file-conventions :as file-conventions]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [datomic.api :as d]
            [clojure.core.async :as a]
            [com.vendekagonlabs.unify.db.backend :as backend]
            [com.vendekagonlabs.unify.db.query :as dq]
            [com.vendekagonlabs.unify.db.transact :as db.tx]
            [clojure.tools.logging :as log]
            [com.vendekagonlabs.unify.db.schema :as schema]
            [clojure.java.io :as io]
            [clojure.string :as str]))


(defn fetch-info [database]
  (let [info (backend/database-info database)]
    (if (some? info)
      (assoc info :database database)
      (let [msg (str "Couldn't load datomic info for branch " database)]
        (throw (ex-info
                 msg
                 {:remote/access {:message msg}}))))))

(defn get-connection [info]
  (d/connect (:uri info)))

(defn latest-db [info]
  (d/db (get-connection info)))

(defn exists? [datomic-uri]
  (try
    (d/connect datomic-uri)
    true
    (catch RuntimeException e
      ;; since no ex-info on distributed Datomic, and only get RuntimeException,
      ;; this string hack lets us see if we get failure for the _right_ reason.
      (let [msg (.getMessage e)]
        (if (and msg (.startsWith msg "Could not find"))
          false
          (throw (ex-info "Could not connect to Datomic."
                          {:datomic/could-not-connect msg})))))))

(defn transact-bootstrap-data
  "Transact bootstrap data."
  [conn local-file-path]
  (a/<!! (:result (db.tx/run-txns! conn [local-file-path] 20))))

(defn tx-effect?
  "Given a connection and map with tx-data and query, determines whether or not transacting
  the data in tx-data would change the results of query. Note: work is done on `conn`, so
  be wary of races and don't rely on this outside of e.g. constrained additive schema."
  [conn {:keys [tx-data query name]}]
  (let [db (d/db conn)
        {:keys [db-after]} (d/with db tx-data)]
    (not= (dq/q+retry query db)
          (dq/q+retry query db-after))))

(defn fulfilled?
  "Given a validation map, returns `true` if this database fulfills validation
  described in map. A validation map consists of a query and its expected results."
  [db {:keys [count entity-id]}]
  (let [query `[:find (count ?e) .
                :where
                [?e ~entity-id]]]
    (= count (dq/q+retry query db))))


(defn apply-schema [schema-directory datomic-uri]
  (let [conn (d/connect datomic-uri)]
    (doseq [raw-tx (schema/schema-txes schema-directory)]
      ;; if a schema attr is not indexed, we add index true. this allows us to keep
      ;; schema edn in resources datomic impl agnostic while optimizing on-prem queries.
      (let [tx (update-in raw-tx [:tx-data]
                          (fn [tx-data]
                            (mapv (fn [schema-ent]
                                    (if (and (:db/valueType schema-ent)
                                             (not (:db/unique schema-ent)))
                                      (assoc schema-ent :db/index true)
                                      schema-ent))
                                  tx-data)))]
        (if (tx-effect? conn tx)
          (do (log/info ::schema (:name tx) " not in database, transacting.")
              (db.tx/sync+retry conn (:tx-data tx)))
          (log/info ::schema "Skipping schema install for: " (:name tx)))))))

(defn version [datomic-uri]
  (let [conn (d/connect datomic-uri)
        db (d/db conn)]
    ;; NOTE: this query assumes there is only one schema version entity, need to
    ;; ensure this constraint is satisfied at update/transaction time.
    (d/q '[:find ?v .
           :where
           [_ :unify.schema/version ?v]]
         db)))

(defn install-seed-data
  [conn seed-data-dir {:keys [include-proprietary] :as _opts}]
  (let [index-file (io/file seed-data-dir "index.edn")
        index-data (util.io/read-edn-file index-file)
        seed-data-index (if-not include-proprietary
                          (remove :proprietary index-data)
                          index-data)]
    (doseq [dataset seed-data-index]
      (if-not (fulfilled? (d/db conn) dataset)
        (let [{:keys [name files]} dataset]
          (log/info ::bootstrap-data "Dataset " name " not present, installing.")
          (doseq [f files]
            (println "\n" (.toString f) "\n")
            (transact-bootstrap-data conn (io/file seed-data-dir f))))
        (log/info ::bootstrap-data "Dataset " (:name dataset) " already present, skipping.")))))

(defn init
  "Loads all base schema, enums, and metamodel into database if necessary."
  [datomic-uri & {:keys [schema-directory
                         seed-data-directory
                         include-proprietary]}]
  (let [_ (d/create-database datomic-uri)
        _ (do
            (log/info "Database created."))
        conn (d/connect datomic-uri)
        _ (log/info "Connected to database")]
    (apply-schema schema-directory datomic-uri)
    (when seed-data-directory
      (install-seed-data conn seed-data-directory
                         {:include-proprietary include-proprietary}))
    datomic-uri))

(defn version->map
  [version-str]
  (zipmap [:major :minor :revision]
          (->> (str/split version-str #"\.")
               (map #(Long/parseLong %)))))

(defn compare-schema-version
  "Compares schema at datomic-uri to cached schema in unify URI. Returns a kw indicating whether
  or not the schema are :incompatible, :identical, or :compatible"
  [schema-directory datomic-uri]
  (let [db-schema-map (-> datomic-uri
                          (version)
                          (version->map))
        unify-schema-map (-> schema-directory
                             (schema/version)
                             (version->map))]
    (cond
      (or (not= (:major db-schema-map) (:major unify-schema-map))
          (not= (:minor db-schema-map) (:minor unify-schema-map)))
      :incompatible

      (= (:revision db-schema-map) (:revision unify-schema-map))
      :identical

      :else
      :compatible)))


(defn ensure-db
  "Returns map with schema included as a key, will throw at Datomic call level if
  unable to connect to database. Will also throw if versions are incompatible."
  [working-dir datomic-uri]
  (let [import-schema-dir (file-conventions/working-dir-schema-dir working-dir)
        version-outcome (compare-schema-version import-schema-dir datomic-uri)]
    (cond
      (= version-outcome :identical)
      datomic-uri

      (= version-outcome :compatible)
      (do (log/info "Compatible schema installed, applying necessary updates.")
          (init datomic-uri :schema-directory import-schema-dir)
          datomic-uri)

      (= version-outcome :incompatible)
      (throw (ex-info "Version of candel schema in database is not compatible."
                      {:candel.schema/version {:db/version    (version datomic-uri)
                                               :unify/version (schema/version import-schema-dir)}})))))


(defn contains-txn?
  "Does the database contain the id (ie. :unify.import.tx/id)"
  [db tx-id]
  (some?
    (ffirst (d/q '[:find ?e
                   :in $ ?id
                   :where
                   [?e :unify.import.tx/id ?id]] db tx-id))))

(defn head
  "Returns metadata about the last transaction:

  {:timestamp ...
   :tx-id ...
   :import-name ...}"
  [db]
  (let [txn (ffirst
              (d/q
                '[:find (max 1 ?tx)
                  :where
                  [?tx :db/txInstant]]
                db))
        txn-data (d/touch (d/entity db (first txn)))
        import-name (if (contains? txn-data :unify.import.tx/import)
                      (-> (d/touch (d/entity db (-> (:unify.import.tx/import txn-data)
                                                    :db/id)))
                          :unify.import/name)
                      (throw (ex-info "No datasets transacted"
                                      {:error :no-imports-on-database})))]
    {:timestamp   (:db/txInstant txn-data)
     :tx-id      (:unify.import.tx/id txn-data)
     :import-name import-name}))

(defn ordered-imports
  "Returns all import entity data. The import entity is the first entity to be
  imported during transact. They are ordered by date and have the form:
  {:name ...
   :tx-id <transaction entity id>
   :timestamp ...}"
  [db]
  (->> (d/q '[:find ?name ?tx
              :where
              [_ :unify.import/name ?name ?tx]]
            db)
       (map
         (fn [[name tx-id]]
           (let [tx (d/touch (d/entity db tx-id))]
             {:timestamp   (:db/txInstant tx)
              :import-name name
              :ent-id      tx-id})))
       (sort-by
         :timestamp
         #(.before %2 %1))))
