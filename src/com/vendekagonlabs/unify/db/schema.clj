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
            [clojure.string :as str]
            [com.vendekagonlabs.unify.db.indexes :as indexes]
            [com.vendekagonlabs.unify.db.query :as dq]
            [clojure.set :as set]))


(defn read-import-schema [schema-dir fname]
  (let [fpath (io/file schema-dir fname)]
    (util.io/read-edn-file fpath)))

(defn base-schema [schema-dir]
  (read-import-schema schema-dir "schema.edn"))
(defn enums [schema-dir]
  (read-import-schema schema-dir "enums.edn"))
(defn metamodel [schema-dir]
  (read-import-schema schema-dir "metamodel.edn"))
(defn unify-meta []
  (-> (io/resource "unify-schema.edn")
      (util.io/read-edn-file)))

;; for metamodel inference backing, etc.
(def cached (clojure.java.io/resource "cached-schema.edn"))

(def new-ident-q
  '[:find (count ?i)
    :with ?e
    :where [?e :db/ident ?i]])

(defn schema-txes
  "Returns an ordered set of all schema transactions."
  [schema-dir]
  (let [read-schema-file (partial read-import-schema schema-dir)
        metamodel-edn (read-schema-file "metamodel.edn")]
    [{:name :base-schema
      :query new-ident-q
      :tx-data (read-schema-file "schema.edn")}
     {:name :enums
      :query new-ident-q
      :tx-data (read-schema-file "enums.edn")}
     {:name :metamodel-attr
      :query new-ident-q
      :tx-data (first metamodel-edn)}
     {:name :metamodel-entities
      :query '[:find (count ?k)
               :where [?k :kind/name]]
      :tx-data (second metamodel-edn)}
     {:name :metamodel-refs
      :query '[:find (count ?p)
               :with ?c
               :where [?p :ref/to ?c]]
      :tx-data (last  metamodel-edn)}
     {:name :com.vendekagonlabs.unify.import.tx-data/metadata
      :query new-ident-q
      :tx-data (unify-meta)}]))

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

