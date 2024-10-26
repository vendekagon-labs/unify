(ns com.vendekagonlabs.unify.db.schema.compile.json-schema
  "Functionality and supporting utilities for generating a json schema from a Unify
  schema and metamodel."
  (:require [charred.api :refer [write-json-str]]
            [com.vendekagonlabs.unify.db.metamodel :as metamodel]
            [com.vendekagonlabs.unify.db.schema :as schema]))

(def db-type-lookup
  {:db.type/bigdec "number"
   :db.type/float "number"
   :db.type/double "number"
   :db.type/bigint "integer"
   :db.type/long "integer"
   :db.type/keyword "string"
   :db.type/string "string"
   :db.type/ref "string"
   :db.type/symbol "string"
   :db.type/uri "string"
   :db.type/instant "string"})

(defn ->root-schema-definition [schema]
  (let [{:keys [unify.schema/version
                unify.schema/name]} (-> schema first :index/unify-schema-metadata)
        schema-info-str (str name "-" version "-import-config")]
    {:$schema "https://json-schema.org/draft/2020-12/schema"
     :$id (format "https://www.unifybio.org/%s/%s/%s/schemas/import-config"
                  (namespace name) (name name) version)
     :title schema-info-str
     :description "A JSON schema generated by Unify, intended for use in validating yaml config files dynamically in editors for this particular schema and version."
     :type "object"}))

(def import-properties
  {"unify/import"
   {:type "object"
    :properties
    {:user {:description "The email address of the user who created this import."
            :type "string"
            :format "email"}
     :mappings {:description "Relative path of the mappings.edn file that specifies enum renaming."
                :type "string"}}
    :required ["user"]}})

(defn value-attr-properties
  "Given a set of attributes, returns JSON schema properties that allow for
  both the correct scalar name and type of the attribute as well as Unify syntax
  that can be used to specify it being set from a file."
  [value-attrs]
  (->> value-attrs
       (map (fn [{:keys [:db/ident :db/valueType :db/doc]}]
              (let [base-type (get db-type-lookup valueType "string")]
                {(name ident) (merge {:description doc}
                                     (if (= base-type "string")
                                       {:type "string"}
                                       {:oneOf [{:type "string"}
                                                {:type base-type}]}))})))
       (apply merge)))

(defn gather-attributes
  "Given a Unify schema and a kind name as keyword, returns the attributes in the
  schema that correspond to that kind."
  [schema kind]
  (let [ident-index (-> schema first :index/idents)
        kind-name-str (name kind)
        kind-attrs (filter
                     (fn [[ident _attr-map]]
                       (= (namespace ident) kind-name-str))
                     ident-index)]
    (into {} kind-attrs)))

(declare ->properties)

(defn nest-child
  "Returns a nested JSON Schema object as an attribute on its parent
  and recursively resolves properties for the child and all
  its children."
  [schema {:keys [db/ident db/cardinality child-kind] :as _child-attr}]
  (let [cardinality' (-> cardinality :db/ident)
        property-name (name ident)]
    (if (= :db.cardinality/one cardinality')
      {property-name {:type "object"
                      :properties (->properties
                                    schema
                                    child-kind)}}
      {property-name {:type "array"
                      :items {:type "object"
                              :properties (->properties schema child-kind)}}})))

(defn other-ref-properties
  "Returns the JSON Schema properties for ref attributes."
  [ref-attrs]
  (->> ref-attrs
       (map (fn [{:keys [:db/ident :db/doc]}]
              {(name ident) {:type "string"
                             :description doc}}))
       (apply merge)))

(defn enum-properties
  "Returns the JSON Schema properties for enum attributes"
  [enum-attrs]
  (->> enum-attrs
       (map (fn [{:keys [:db/ident :db/doc]}]
              {(name ident) {:type "string"
                             :description doc}}))
       (apply merge)))

(defn group-attributes
  "Groups and annotates attribute maps for a particular kind into different
  categories that will drive different generative patterns for different JSON
  schema property types and allow the use of different Unify
  schema, e.g. nested child refs, strings or ref resolving forms
  for other reference types, enums, data literals, etc."
  [schema child-kinds attributes]
  (let [child-kinds-set (set child-kinds)]
    (->> (vals attributes)
         (keep (fn [attr-map]
                 ;; if not a reference, it's a flat value type attribute
                 (if-not (= :db.type/ref (-> attr-map :db/valueType :db/ident))
                   (assoc attr-map ::attr-type ::value)
                   ;; if it is a reference, we have three cases to disambiguate
                   (let [ref-targets (metamodel/kind-ref? schema (:db/ident attr-map))]
                     (cond
                       ;; if we don't point to another kind, it's an enum
                       (not ref-targets)
                       (assoc attr-map ::attr-type ::enum)
                       ;; if we point to another kind and it's in the set of children
                       ;; for this kind, it's a child ref (nested object in schema)
                       (and ref-targets
                            (child-kinds-set (:unify.ref/to ref-targets)))
                       (assoc attr-map ::attr-type ::child
                                       :child-kind (:unify.ref/to ref-targets))
                       ;; if we don't point to a child, it's a sibling or undifferentiated ref
                       ;; to another kind somewhere else in the hierarchy
                       :else
                       (assoc attr-map ::attr-type ::ref))))))
         (group-by ::attr-type))))

(def unify-special-forms
  {"unify/variable" {:type "string"}
   "unify/value"    {:type "string"}
   "unify/constants" {:type "object"}
   "unify/reverse" {:type "object"
                    :properties {"unify/rev-variable" {:type "string"}
                                 "unify/rev-attr" {:type "string"}}}
   "unify/na" {:type "string"}
   "unify/omit-if-na" {:type "array"
                       :items {:type "string"}}
   "unify/input-tsv-file" {:oneOf [{:type "string"}
                                   {:type "object"
                                    :properties
                                    {"unify.glob/directory" {:type "string"}
                                     "unify.glob/pattern" {:type "string"}}}]}
   ;; open properties for simpler json schema form, don't have syntax or context
   ;; to do this intelligently, though we could try with a push-down at some point
   "unify/variables" {:type "object"}})

(def ->kind-children-lookup
  (memoize
   (fn ->kind-children-lookup*
     [schema]
     (let [kind-info (:index/kinds (first schema))]
       (->> kind-info
            (keep (fn [[kind attrs]]
                    (when-let [parent-kind (:unify.kind/parent attrs)]
                      {parent-kind [kind]})))
            (apply merge-with concat))))))


(defn get-children
  "Given a schema and a kind name, gets the children for given kind via
  reverse lookup from :unify.kind/parents metamodel annotation."
  [schema kind]
  (let [lookup (->kind-children-lookup schema)]
    (get lookup kind)))

(defn ->properties
  "Given a Unify schema and a kind name, produces a map of JSON schema properties
  for said kind name using schema inference."
  [schema kind]
  (let [attributes (gather-attributes schema kind)
        children (get-children schema kind)
        {:keys [::value ::enum ::child ::ref]} (group-attributes schema children attributes)
        child-properties (when child
                           (apply merge (map (partial nest-child schema) child)))
        enum-properties (when enum
                          (enum-properties enum))
        non-child-ref-properties (when ref
                                   (other-ref-properties ref))
        attr-properties (when value
                          (value-attr-properties value))
        all-properties (merge unify-special-forms
                              child-properties
                              enum-properties
                              attr-properties
                              non-child-ref-properties)]
    {kind {:type "object"
           :properties all-properties}}))


(defn ->kind-tree
  "Given a Unify schema, recursively constructs a tree of kind properties via anonymous
  JSON Schema object type nesting."
  [schema]
  (let [kind-info (-> schema first :index/kinds)
        ref-kinds (->> kind-info
                       (vals)
                       (filter :unify.kind/ref-data)
                       (map :unify.kind/name))
        root-kinds (->> kind-info
                        (vals)
                        (remove :unify.kind/parent)
                        (remove :unify.kind/ref-data)
                        (map :unify.kind/name))]
    ;; concat atypical for apply, but ambiguity w/merge and seq-able maps
    (apply merge (concat (map (partial ->properties schema) ref-kinds)
                         (map (partial ->properties schema) root-kinds)))))


(defn generate
  ([schema]
   (let [root-schema (->root-schema-definition schema)
         kind-schemas (->kind-tree schema)]
     (merge root-schema
            {:required ["unify/import" "dataset"]
             :properties
             (merge import-properties kind-schemas)})))

  ([]
   (let [schema (schema/get-metamodel-and-schema)]
     (generate schema))))

(comment
  (let [as-generated (generate)
        to-json (write-json-str as-generated)]
    (spit "first-generated-schema.json" to-json)))
