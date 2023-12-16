(ns com.vendekagonlabs.unify.db.schema.compile
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [com.vendekagonlabs.unify.util.io :as util.io]))

(defn add-namespace
  "Namespaces a keyword `kw` with the `ns-val` which can either be a string,
  a keyword without a namespace, or a namespaced keyword."
  [ns-val kw]
  (let [ns-str (cond
                 (and (keyword? ns-val)
                      (namespace ns-val))
                 (let [kw-name (name ns-val)
                       kw-ns (namespace ns-val)]
                   (str kw-ns "." kw-name))

                 (keyword? ns-val)
                 (name ns-val)

                 (string? ns-val)
                 ns-val

                 :else
                 (throw (ex-info "Invalid namespace identifier!"
                                 {:namespace-value ns-val
                                  :valid-types [:string :keyword]})))]
    (keyword ns-str (name kw))))

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

(def cardinality-lookup
  {:cardinality-one :db.cardinality/one
   :cardinality-many :db.cardinality/many})

(defn process-attribute
  [kind-name [attr-kw attr-type attr-card attr-doc]]
  (let [ns-attr-kw (add-namespace kind-name attr-kw)
        datomic-attr-type (if (map? attr-type)
                            :db.type/ref
                            (add-namespace "db.type" attr-type))
        datomic-schema {:datomic/schema
                        [{:db/ident ns-attr-kw
                          :db/valueType datomic-attr-type
                          :db/cardinality (get cardinality-lookup attr-card)
                          :db/doc attr-doc}]}]
    (merge datomic-schema
           (when-let [ref-target (and (map? attr-type)
                                      (:ref-to attr-type))]
             {:unify/metamodel-refs [{:db/id ns-attr-kw
                                      :unify.ref/from kind-name
                                      :unify.ref/to ref-target}]})
           (when-let [enum-set (and (map? attr-type)
                                    (:enum-of attr-type))]
             {:unify/enums (mapv (comp
                                   (fn [enum-name]
                                     {:db/ident enum-name})
                                   (partial add-namespace ns-attr-kw))
                                 enum-set)}))))

(defn attributes->schema
  [kind-name attrs-def]
  (let [processed-attributes (map (partial process-attribute kind-name) attrs-def)]
    (apply merge-with concat processed-attributes)))

(defn process-kind
  "Enrich kind data with parent if present
  Construct attributes
  Add any ref annotations to ref attributes"
  [kind-name {:keys [id parent attributes] :as _kind-def}]
  (let [{:keys [schema kind]} (process-id kind-name id)
        kind-def (if-not parent
                   kind
                   (assoc kind :unify.kind/parent parent))
        attr-schema (attributes->schema kind-name attributes)]
    (merge-with concat {:datomic/schema schema
                        :unify/metamodel-kinds [kind-def]}
                       attr-schema)))

(defn ->raw-schema
  [unify-schema-edn]
  (apply merge-with concat (for [[kind kind-def] unify-schema-edn]
                             (process-kind kind kind-def))))

(def file-name-lookup
  {:datomic/schema "schema.edn"
   :unify/metamodel "metamodel.edn"
   :unify/enums "enums.edn"})

(defn write-schema
  [file data]
  (binding [*print-length* nil
            *print-namespace-maps* false]
    (spit file
          (with-out-str
            (pp/write data :dispatch pp/code-dispatch)))))

(defn write-schema-dir!
  [schema-dir raw-schema]
  (io/make-parents (io/file schema-dir "schema.edn"))
  (doseq [schema-key [:datomic/schema
                      :unify/enums]]
    (when-let [edn-data (vec (get raw-schema schema-key))]
      (let [out-file (io/file schema-dir (get file-name-lookup schema-key))]
          (write-schema out-file edn-data))))
  (let [metamodel-content [(:unify/metamodel-kinds raw-schema)
                           (:unify/metamodel-refs raw-schema)]]
    (write-schema (io/file schema-dir "metamodel.edn") metamodel-content))

  (comment
    :troubleshooting
    (def example-schema-dsl (util.io/read-edn-file "test/resources/systems/patient-dashboard/schema/unify.edn"))

    ;; Working first cut
    ;; TODO: next
    ;; - create tests of outer API
    ;; - ensure that:
    ;;   - given a schema DSL definition, create a schema directory with expected files
    ;;   - starting unify processes that point to those files work as expected
    ;;   - unify can prepare and transact data that conforms to the schema def in the unify schema dsl
    ;;
    ;; When we have verified these behaviors:
    ;;   - add a `unify compile-schema --unify-schema --schema-directory` task to run this on the CLI
    (def example-id-data
      (get-in example-schema-dsl [:patient :id])
      (process-id :patient example-id-data))
    (def raw-schema-example
      (->raw-schema example-schema-dsl))
    (write-schema-dir! "temp-blah" raw-schema-example)
    (keys raw-schema-example)
    (pp/pprint (:datomic/schema raw-schema-example))
    (pp/pprint (:unify/metamodel raw-schema-example))
    (pp/pprint (:unify/enums raw-schema-example))
    (:unify/metamodel raw-schema-example)
    (namespace :patient/sex)
    (namespace :patient)
    (mapv (partial add-namespace :patient/sex) [:male :female :other :unknown])

    (process-kind :patient (:patient example-schema-dsl))
    (process-kind :physician (:physician example-schema-dsl))
    (process-kind :medication (:medication example-schema-dsl))
    (process-kind :prescription (:prescription example-schema-dsl))

    (attributes->schema :patient (get-in example-schema-dsl [:patient :attributes]))))

