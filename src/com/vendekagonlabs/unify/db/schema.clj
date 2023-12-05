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
(ns com.vendekagonlabs.unify.db.schema
  (:require [clojure.java.io :as io]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [com.vendekagonlabs.unify.db.indexes :as indexes]
            [com.vendekagonlabs.unify.db.query :as dq]
            [clojure.set :as set]))

(defn base-schema [] (util.io/read-edn-file (io/resource "schema/schema.edn")))
(defn enums [] (util.io/read-edn-file (io/resource "schema/enums.edn")))
(defn metamodel [] (util.io/read-edn-file (io/resource "schema/metamodel.edn")))
(defn unify-meta [] (util.io/read-edn-file (io/resource "schema/unify-meta.edn")))

;; for metamodel inference backing, etc.
(def cached (clojure.java.io/resource "cached-schema.edn"))

(def new-ident-q
  '[:find (count ?i)
    :with ?e
    :where [?e :db/ident ?i]])

(def schema-txes
  ;; this is an ordered set of all schema transactions required to bring database up to date
  ;; txes will be applied in order, conditionally, if transacting schema would change the
  ;; result of the probing query.
  ;; Queries are starting out simplistic, we can adjust based on how schema actually evolves.
  [{:name :base-schema
    :query new-ident-q
    :tx-data (base-schema)}
   {:name :enums
    :query new-ident-q
    :tx-data (enums)}
   {:name :metamodel-attr
    :query new-ident-q
    :tx-data (first (metamodel))}
   {:name :metamodel-entities
    :query '[:find (count ?k)
             :where [?k :kind/name]]
    :tx-data (second (metamodel))}
   {:name :metamodel-refs
    :query '[:find (count ?p)
             :with ?c
             :where [?p :ref/to ?c]]
    :tx-data (last  (metamodel))}
   {:name :com.vendekagonlabs.unify.import.tx-data/metadata
    :query new-ident-q
    :tx-data (unify-meta)}])

(defn cache
  "Write schema to resources (wrapped in vec for eagerness, readability)"
  [schema]
  (binding [*print-length* nil]
    (spit cached (vec schema))))

(defn get-all-kind-data
  "Get all the entities representing the kinds in the system"
  [db]
  (flatten (dq/q+retry '[:find (pull ?e [*
                                         {:kind/attr [:db/ident]}
                                         {:kind/context-id [:db/ident]}
                                         {:kind/need-uid [:db/ident]}
                                         {:kind/synthetic-attr-name [:db/ident]}])
                         :where [?e :kind/name]] db)))

(defn get-all-schema
  "Query database for installed attributes"
  [db]
  (flatten (dq/q+retry '[:find (pull ?e [*
                                         {:db/valueType [:db/ident]}
                                         {:db/cardinality [:db/ident]}
                                         {:db/unique [:db/ident]}])
                                         ;; also metamodel ref from and to on attr
                         :where [_ :db.install/attribute ?e]] db)))

(defn get-non-attr-idents
  "Returns non-attribute idents.

  Non-attribute idents are assumed to be valid enum idents (this is mostly true, and
  incidental non-enum idents are unlikely to conflict w/user typos.)"
  [db]
  (let [attr-idents (dq/q+retry '[:find [?ident ...]
                                  :where
                                  [_ :db.install/attribute ?a]
                                  [?a :db/ident ?ident]]
                                db)
        all-idents (dq/q+retry '[:find [?ident ...]
                                 :where
                                 [?e :db/ident ?ident]]
                               db)]
    (set/difference (set all-idents)
                    (set attr-idents))))

(defn get-metamodel-and-schema
  "Return the schema + metamodel data structure"
  ([db]
   (let [flat-schema (concat (map #(assoc % :db.install/_attribute true)
                                  (get-all-schema db))
                             (get-all-kind-data db))
         core-indexes (indexes/all flat-schema)
         enums (get-non-attr-idents db)
         indexes (assoc core-indexes :index/enum-idents enums)]
     (concat [indexes] flat-schema)))
  ([]
   (util.io/read-edn-file cached)))

(defn version
  []
  (-> (keep (fn [{:keys [db/ident] :as ent}]
              (when (= ident :candel/schema)
                ent))
            (util.io/read-edn-file (io/resource "schema/enums.edn")))
      (first)
      (:candel.schema/version)))

