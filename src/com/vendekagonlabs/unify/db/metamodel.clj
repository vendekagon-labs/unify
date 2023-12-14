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
(ns com.vendekagonlabs.unify.db.metamodel
  "Utility functions for accessing metamodel data
  'schema' argument to these functions is a data structure
  containing the DB schema as well as the metamodel data
  as returned by the db-util/get-metamodel-and-schema function.
  Note that this includes as the first element indexes for O(1)
  access (hash-map lookup) for common queries."
  (:require [com.vendekagonlabs.unify.util.text :refer [->str]]
            [clojure.tools.logging :as log]
            [com.vendekagonlabs.unify.db.indexes :as indexes]
            [com.vendekagonlabs.unify.util.collection :as util.coll]
            [clojure.string :as str]
            [com.vendekagonlabs.unify.db.schema :as db.schema]))

(defn kind-by-name
  "Return all data for any kinds that match provided kind-name.
  First argument is destructured schema containing indexes."
  [[{:keys [:index/kinds]}] k]
  (get kinds k))

(defn get-ref
  "Get only the Datomic references from the schema data structure"
  [[{:keys [:index/refs]}] ident]
  (get refs ident))

(defn refs-by-kind
  "Returns a set of all kinds that this kind-name references."
  [[{:keys [:index/refs]}] kind-name]
  (reduce
    (fn [coll {:keys [:unify.ref/from :unify.ref/to] :as m}]
      (if (= from kind-name)
        (conj coll to)
        coll))
    #{}
    (vals refs)))

(defn ref?
  "Return true if the given attribute is a reference"
  [[{:keys [index/refs]}] attr-ident]
  (get refs attr-ident))

(defn kind-ref?
  "Return the :from and :to k/vs for the given reference attribute, truthy when
  this is a reference to a kind entity."
  [schema ref-attr-ident]
  (let [ref-ent (get-ref schema ref-attr-ident)
        ref-links (select-keys ref-ent [:unify.ref/from :unify.ref/to])]
    (not-empty ref-links)))

(defn kind-parent
  "Returns the :unify.kind/name of the parent of the specified kind"
  [schema kind-name]
  (:unify.kind/parent (kind-by-name schema kind-name)))

(defn card-many?
  "Returns true if the provide attributes is :cardinality/many in the schema."
  [[{:keys [index/card-many]}] attr]
  (get card-many attr))

;; this may be a problem with :target kind (multi), relies on the constraint that no target has a context-id
(defn kind-context-id
  "Returns the context-id (locally identifying attribute) for the specified kind"
  [schema kind-name]
  (get-in (kind-by-name schema kind-name) [:unify.kind/context-id :db/ident]))

(defn ref-data?
  "Indicates whether the specified kind is reference data (cross-study data)"
  [schema kind-name]
  (:unify.kind/ref-data (kind-by-name schema kind-name)))

(defn need-uid?
  "Returns the UID attribute name (as a kw) if this kind requires a generated UID,
  returns nil for kinds that don't require a UID"
  [schema kind-name]
  (get-in (kind-by-name schema kind-name) [:unify.kind/need-uid :db/ident]))

(def synthetic-sep
  "The character that seperates component values of a synthetic attribute."
  "-")

(defn synthetic-attr?
  "Returns the target attribute name (as a kw) for the synthesized value if this kind
  requires a synthetic attribute. returns nil for kinds that don't require a synthetic attribute."
  [schema kind-name]
  (get-in (kind-by-name schema kind-name) [:unify.kind/synthetic-attr-name :db/ident]))

(defn synthetic-attr-components
  "Returns the vector of attribute names whose values are used to synthesize the synthetic
  attribute value."
  [schema kind-name]
  (let [c (get-in (kind-by-name schema kind-name) [:unify.kind/synthetic-attr-components])]
    (mapv
      #(:unify.kind.sythetic-attr-components/name %)
      (sort-by :unify.kind.synthetic-attr-components/idx c))))

(defn enum-ident?
  "Returns true if the provided ident is in the enums index in the schema."
  [[{:keys [index/enum-idents]}] ident]
  (enum-idents ident))

(defn allow-create?
  "Returns true if the provided kind-name corresponds to a kind that permits
  creation of reference data during import"
  [schema kind-name]
  (:unify.kind/allow-create-on-import (kind-by-name schema kind-name)))

(defn all-kind-names
  [schema]
  (->> (keep :unify.kind/name schema)
       (into #{}) (into [])))

(defn allowed-ref-data
  [schema]
  (filter (partial allow-create? schema) (all-kind-names schema)))

(defn attribute-idents
  "Returns idents for all attributes in the schema, other than built-in :db*/ namespaced
  attributes."
  [schema]
  (->> schema
       (filter :db.install/_attribute)
       (map :db/ident)
       (remove #(str/starts-with? (namespace %) "db"))))

(defn attribute-ident?
  "Returns attr if it's in schema, otherwise nil. Intended use is via truthiness for predicate."
  [schema ident]
  (let [attrs (set (attribute-idents schema))]
    (attrs ident)))

(defn family-tree-ids
  "Returns a stack of a kind and all its parents (with the passed kind at
  the top of the stack)."
  [schema kind]
  (reverse (loop [child kind
                  tree []]
             (if-let [parent (kind-parent schema child)]
               (recur parent (conj tree (kind-context-id schema child)))
               (conj tree (kind-context-id schema child))))))

(defn node-context->kind
  "Find the :unify.ref/to kind/name for the specified keyword-context list
   'refs' should be a list of all ref attr maps that includes :unify.ref/from and :unify.ref/to
      as returned by meta/all-refs
    'kws-ctx' is an individual keyword-context-list for a given path within a map"
  [schema kws-ctx]
  (if (<= (count kws-ctx) 1)
    (first kws-ctx)
    (let [this-ent (last kws-ctx)
          wo-this-ent (butlast kws-ctx)
          par (if (= (count wo-this-ent) 1)
                (first wo-this-ent)
                (node-context->kind schema wo-this-ent))
          parent-kw (keyword (name par) (name this-ent))
          parent-ent (get-ref schema parent-kw)]
      (if parent-ent
        (:unify.ref/to parent-ent)
        (throw
          (ex-info (str "No match in references for : " parent-kw)
                   {:parse/reference parent-kw}))))))

(defn ref-linked
  [schema attr attr-k link-k]
  (reduce-kv (fn [a _ v]
               (if (= (get v attr-k) attr)
                 (conj (or a #{}) (get v link-k))
                 a))
             nil
             (:index/idents (first schema))))

(defn ref-dependencies
  [schema attr]
  (ref-linked schema attr :unify.ref/to :unify.ref/from))

(defn unique?
  "Returns true if the provided attr is :db.unique (value or identity) in the schema."
  [schema attr]
  (:db/unique (first (filter #(= (:db/ident %) attr) schema))))

(defn all-uids
  "Returns (as a set) all the UID attributes names in the schema"
  [schema]
  (set (keys (:index/uids (first schema)))))

(defn eid->ident*
  "For a given eid present in the schema, returns the ident, if it exists."
  [schema id]
  (->> schema
       (filter #(= id (:db/id %)))
       (map :db/ident)
       (first)))

(def eid->ident (memoize eid->ident*))

(defn kind->attr*
  "For a given kind name (non-ns keyword form), returns an attr present on that kind definition."
  [schema kind-name attr-kw]
  (let [{:keys [db/id]} (->> schema
                             (filter #(= kind-name (:unify.kind/name %)))
                             (first)
                             (attr-kw))]
    (eid->ident schema id)))

(def kind->attr (memoize kind->attr*))

(defn attr->db-type
  "Given an attribute specified by ident, returns the db/valueType for that attribute."
  [schema attribute-ident]
  (get-in schema [0 :index/idents attribute-ident :db/valueType :db/ident]))

(defn matrix-key-attr
  "Returns the ident of the attribute which stores the matrix storage key attribute
   for this entity kind as specified by kind-name in the metamodel, or nil if the
   kind-name does not have a matrix storage key specified."
  [schema kind-name]
  (kind->attr schema kind-name :unify.kind.matrix-blob/storage-key-attribute))


(defn matrix-data-type-attr
  "Returns the ident of the attribute which stores the matrix storage key attribute
   for this entity kind as specified by kind-name in the metamodel, or nil if the
   kind-name does not have a matrix storage key specified."
  [schema kind-name]
  (kind->attr schema kind-name :unify.kind.matrix-blob/data-type-attribute))


(comment
  (def schema (db.schema/get-metamodel-and-schema))
  (matrix-key-attr schema :measurement-matrix)
  (matrix-data-type-attr schema :measurement-matrix))
