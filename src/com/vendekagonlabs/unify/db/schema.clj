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
            [datomic.api :as d]
            [com.vendekagonlabs.unify.util.io :as util.io]
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
(defn unify-schema []
  (-> (io/resource "unify-schema.edn")
      (util.io/read-edn-file)))

;; for metamodel inference backing, etc.
(def cached ".unify/cached-schema.edn")

(def new-ident-q
  '[:find (count ?i)
    :with ?e
    :where [?e :db/ident ?i]])

(defn schema-txes
  "Returns an ordered set of all schema transactions."
  [schema-dir]
  (let [read-schema-file (partial read-import-schema schema-dir)
        [metamodel-entities
         metamodel-refs] (read-schema-file "metamodel.edn")]
    [{:name    :unify.schema/metadata
      :query   new-ident-q
      :tx-data (unify-schema)}
     {:name    :base-schema
      :query   new-ident-q
      :tx-data (read-schema-file "schema.edn")}
     {:name    :enums
      :query   new-ident-q
      :tx-data (read-schema-file "enums.edn")}
     {:name    :metamodel-entities
      :query   '[:find (count ?k)
                 :where [?k :unify.kind/name]]
      :tx-data metamodel-entities}
     {:name    :metamodel-refs
      :query   '[:find (count ?p)
                 :with ?c
                 :where [?p :unify.ref/to ?c]]
      :tx-data metamodel-refs}]))


(defn cache
  "Write schema to resources (wrapped in vec for eagerness, readability)"
  [schema]
  (binding [*print-length* nil]
    (spit cached (vec schema))))

(defn get-all-kind-data
  "Get all the entities representing the kinds in the system"
  [db]
  (flatten (dq/q+retry '[:find (pull ?e [*
                                         {:unify.kind/attr [:db/ident]}
                                         {:unify.kind/context-id [:db/ident]}
                                         {:unify.kind/need-uid [:db/ident]}
                                         {:unify.kind/global-id [:db/ident]}
                                         {:unify.kind/synthetic-composite-id [:db/ident]}])
                         :where [?e :unify.kind/name]] db)))

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
         unify-schema-metadata (-> (d/pull db [:unify.schema/version
                                               :unify.schema/name] :unify.schema/metadata))
         core-indexes (indexes/all flat-schema)
         enums (get-non-attr-idents db)
         indexes (assoc core-indexes :index/enum-idents enums
                                     :index/unify-schema-metadata unify-schema-metadata)]
     (concat [indexes] flat-schema)))
  ([]
   (util.io/read-edn-file cached)))

(defn version
  ([]
   (-> "cached-schema.edn"
       (io/resource)
       (util.io/read-edn-file)
       (first)
       (:index/unify-schema-metadata)
       (:unify.schema/version)))
  ([schema-dir]
   (->> (io/file schema-dir "schema.edn")
        (util.io/read-edn-file)
        (filter :unify.schema/version)
        (first)
        (:unify.schema/version))))

(defn copy-schema-dir!
  "Copies the Unify managed schema files from one directory to another.
  Used by prepare, to save a reference schema in a working directory."
  [src-dir dest-dir]
  (util.io/mkdirs! dest-dir)
  (doseq [schema-file ["schema.edn" "metamodel.edn" "enums.edn"]]
    (io/copy (io/file src-dir schema-file)
             (io/file dest-dir schema-file))))
