(ns com.vendekagonlabs.unify.db.schema.compile.json-schema
  "Functionality and supporting utilities for generating a json schema from a Unify
  schema and metamodel."
  (:require [charred.api :refer [write-json-str]]
            [com.vendekagonlabs.unify.db.metamodel :as metamodel]
            [com.vendekagonlabs.unify.db.schema :as schema]))


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

(defn import-properties
  "The JSON schema properties for the import metadata object in the config."
  []
  {"unify/import"
   {:type "object"
    :properties
    {:user {:description "The email address of the user who created this import."
            :type "string"
            :format "email"}
     :mappings {:description "Relative path of the mappings.edn file that specifies enum renaming."
                :type "string"}}
    :required ["user"]}})

(defn attributes->properties
  "Given a set of attributes, returns JSON schema properties that allow for
  both the correct scalar name and type of the attribute as well as Unify syntax
  that can be used to specify it being set from a file."
  [attributes])
  ; an attribute can be set as a literal, or as a field, or as a unify directive of several types,
  ; some of which are only valid if the attribute is of type ref.

(declare ->properties)

(defn nest-child
  "Returns a nested JSON Schema object as an attribute on its parent
  and recursively resolves properties for the child and all
  its children."
  [schema kind-children-lookup attributes child-kind]
  {})

(defn other-ref-properties
  "Returns the JSON Schema properties for ref attributes."
  [schema kind-children-lookup attributes])

(defn enum-properties
  "Returns the JSON Schema properties for enum attributes"
  [schema kind-children-lookup attributes])

(defn group-attributes
  "Groups attributes for a particular kind into different categories
  that will drive different generative patterns for different JSON
  schema property types and allow the use of different Unify
  schema, e.g. nested child refs, strings or ref resolving forms
  for other reference types, enums, data literals, etc."
  [schema kind-children-lookup attributes]
  (let [ref-attributes ()]))


(defn ->properties
  "Given a Unify schema and a kind name, produces a map of JSON schema properties
  for said kind name using schema inference."
  [schema kind-children-lookup kind attributes]
  (for [ref-attrs (keep (fn [attr-map])
                        attributes)
        child-kinds (get kind-children-lookup kind)
        child-properties (apply merge (map (partial nest-child schema attributes kind-children-lookup) child-kinds))
        enum-properties (enum-properties schema kind-children-lookup attributes)
        non-child-ref-properties (other-ref-properties schema kind-children-lookup attributes)
        attr-properties (attributes->properties attributes)]
    (merge child-properties enum-properties attr-properties)))

(defn gather-attributes
  "Given a Unify schema and a kind name as keyword, returns the attributes in the
  schema that correspond to that kind."
  [schema kind]
  (let [ident-index (-> schema first :index/idents)
        kind-name-str (name kind)
        kind-attrs (filterv
                     (fn [[ident attr-map]]
                       (= (namespace ident) kind-name-str))
                     ident-index)]
    (into {} kind-attrs)))


(defn kind->schema-def
  "Given a Unify in memory schema (indexed) and a kind name as keyword,
  produces a JSON Schema definition for the portion of the import config
  that imports entities of this kind, whether through data literals or
  through input file directives."
  [schema kind-children-lookup kind]
  (let [kind-attributes (gather-attributes schema kind)
        kind-properties (->properties schema kind-children-lookup kind kind-attributes)]))

(defn ->kind-children-lookup
  "Given the kind info as structured in the in-memory kind index, constructs a forward
  link from parents to children."
  [schema]
  (let [kind-info (:index/kinds (first schema))]
    (->> kind-info
         (keep (fn [[kind attrs]]
                 (when-let [parent-kind (:unify.kind/parent attrs)]
                   {parent-kind [kind]})))
         (apply merge-with concat))))

(defn ->kind-tree
  "Given a Unify schema, recursively constructs a tree of kind properties via anonymous
  JSON Schema object type nesting."
  [schema]
  (let [kind-info (-> schema first :index/kinds)
        ref-kinds (->> kind-info
                       (vals)
                       (filter :unify.kind/ref-data)
                       (mapv :unify.kind/name))
        root-kinds (->> kind-info
                        (vals)
                        (remove :unify.kind/parent)
                        (remove :unify.kind/ref-data)
                        (mapv :unify.kind/name))
        child-lookup (->kind-children-lookup schema)]
    (apply merge (mapv (partial kind->schema-def child-lookup) ref-kinds)
                 (mapv (partial kind->schema-def schema child-lookup) root-kinds))))


(defn generate
  ([schema]
   (let [root-schema (->root-schema-definition schema)
         import-schema (import-properties)
         kind-schemas (->kind-tree schema)]
     (merge root-schema
            {:required ["unify/import" "dataset"]
             :properties
             (merge import-schema
                    kind-schemas)})

     {}))
  ([]
   (let [schema (schema/get-metamodel-and-schema)]
     (generate schema))))

(comment
  (def ex-schema (schema/get-metamodel-and-schema))
  (keys (first ex-schema))

  (def ex-attributes (gather-attributes ex-schema :subject))
  (keep (fn [[ident attr-map]]
           (when (:db.type/ref attr-map)
             (cond
               (not (metamodel/kind-ref? ident))
               [ident (merge attr-map ::enum)]
               (metamodel/refs-by-kind))))
        ex-attributes)
  (metamodel/all-kind-names ex-schema)
  (->root-schema-definition ex-schema)
  (def kind-info (:index/kinds (first ex-schema)))
  (keys kind-info)
  (->kind-children-lookup ex-schema)
  (mapv :unify.kind/parent (vals (:index/kinds (first ex-schema))))
  (:unify.kind/parent (:sample (:index/kinds (first ex-schema))))
  (-> ex-schema first :index/kinds seq)

  (:index/unify-schema-metadata (first ex-schema))
  (println
    (write-json-str
      {:test "a test"})))
