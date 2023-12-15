(ns com.vendekagonlabs.unify.db.schema.compile
  (:require [com.vendekagonlabs.unify.util.io :as util.io]))

(defn add-namespace [ns-val kw]
  (let [ns-str (if (keyword? ns-val)
                 (name ns-val)
                 ns-val)]
    (keyword ns-str (name kw))))

(def context-lookup
  {:context :unify.kind/context-id
   :global  :unify.kind/global-id})


(defn process-id
  [kind-name {:keys [type scope attribute doc]}]
  (let [ns-attr (add-namespace kind-name attribute)
        id-attr* {:db/ident ns-attr
                  :db/valueType (add-namespace "db.type" type)
                  :db/cardinality :db.cardinality/one}
        attr-doc (or doc (str "ID field with scope " scope " for entity " kind-name))
        id-attr (assoc id-attr* :db/doc attr-doc)
        base-kind-data {:unify.kind/name kind-name}]
    (cond
      (= :global scope)
      {:schema [(assoc id-attr :db/unique :db.unique/identity)]
       :kind (assoc base-kind-data :unify.kind/global-id ns-attr)}

      (= :context scope)
      (let [unify-uid-attr (add-namespace kind-name :unify-scoped-id)]
        {:schema [id-attr
                  {:db/ident unify-uid-attr
                   :db/valueType :db.type/tuple
                   :db/tupleType   :db.type/string
                   :db/cardinality :db.cardinality/one
                   :db/unique      :db.unique/identity
                   :db/doc (str "Unify generated scoped ID for entities of kind " kind-name)}]
         :kind (merge base-kind-data
                      {:unify.kind/context-id ns-attr
                       :unify.kind/need-uid unify-uid-attr})})
      :else
      (throw (ex-info "Only :global and :context id scopes currently supported!"
                      {:valid-scopes [:context :global]
                       :provided-scope [scope]})))))


(defn attributes->schema [])

(defn process-kind
  "Extract ID
  Enrich kind data with parent if present
  Construct attributes
  Add any ref annotations to ref attributes"
  [kind-name {:keys [id parent attributes] :as _kind-def}]
  (let [{:keys [schema kind]} (process-id kind-name id)
        kind-def (if-not parent
                   kind
                   (assoc kind :unify.kind/parent parent))
        attr-schema []] ;; TODO: attributes->schema
    {:datomic/schema (concat schema attr-schema)
     ;; TODO: add ref annotations to kind-def
     :unify.kind/schema [kind-def]}))



(comment
  (def example-schema-dsl (util.io/read-edn-file "test/resources/systems/patient-dashboard/schema/unify.edn"))
  (def example-id-data
    (get-in example-schema-dsl [:patient :id]))
  (process-kind :patient (:patient example-schema-dsl))
  (process-kind :physician (:physician example-schema-dsl))
  (process-kind :medication (:medication example-schema-dsl))
  (process-kind :prescription (:prescription example-schema-dsl))
  (process-id :patient example-id-data))
