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

(defn ->import-config-properties
  "Given a schema, infers the JSON schema properties for the import config,
  which align with the expected top level keys."
  ;; this will be:
  [schema])

(defn unify-attribute-properties
  "Because an import config can provide literal data OR file import directives,
  we need a way to specify the metadata"
  [])


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
  "Given a set of attributes, returns"
  [attributes])
  ; an attribute can be set as a literal, or as a field, or as a unify directive of several types,
  ; some of which are only valid if the attribute is of type ref.


(defn gather-attributes
  [schema kind])

(defn ->properties
  "Given a Unify schema and a kind name, produces a map of JSON schema properties
  for said kind name using schema inference."
  [schema kind])

(defn ->kind-objects
  "Given a Unify schema, recursively constructs a tree of kind properties via anonymous
  JSON Schema object type nesting."
  [schema])
  ; at root


(defn generate
  ([schema]
   (let [root-schema (->root-schema-definition schema)
         import-schema (import-properties)
         kind-schemas (->kind-objects schema)]
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
  (metamodel/all-kind-names ex-schema)
  (->root-schema-definition ex-schema)
  (keys (first ex-schema))
  (:index/kinds (first ex-schema))
  (:index/unify-schema-metadata (first ex-schema))
  (println
    (write-json-str
      {:test "a test"})))
