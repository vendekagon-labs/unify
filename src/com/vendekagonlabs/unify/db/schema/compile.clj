(ns com.vendekagonlabs.unify.db.schema.compile
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.pprint :as pp]
            [clojure.set :refer [map-invert]]
            [com.vendekagonlabs.unify.db.schema :as schema]
            [com.vendekagonlabs.unify.db.metamodel :as metamodel]
            [com.vendekagonlabs.unify.db.schema.cache :as schema.cache]
            [com.vendekagonlabs.unify.util.io :as util.io])
  (:import (java.io File)))


(defn unnamespaced-keyword? [kw]
  (and (keyword? kw)
       (not (namespace kw))))

(s/def ::simple-datomic-attr-type
  #{:bigdec :bigint :boolean :double :float :instant :keyword
    :long :string :symbol :tuple :uuid :uri :bytes})

(s/def ::tuple-of
  (s/coll-of unnamespaced-keyword? :kind vector?))
(s/def ::tuple-type-def
  (s/keys :req-un [::tuple-of]))

(s/keys ::ref-to unnamespaced-keyword?)
(s/def ::ref-type-def
  (s/keys :req-un [::ref-to]))
(s/def ::enum-of
  (s/coll-of unnamespaced-keyword? :kind vector?))
(s/def ::enum-type-def
  (s/keys :req-un [::enum-of]))
(s/def ::type-def
  (s/or :simple-type ::simple-datomic-attr-type
        :unify-type (s/and (s/map-of unnamespaced-keyword? some?)
                           (s/or :tuple-def ::tuple-type-def
                                 :ref-def ::ref-type-def
                                 :enum-def ::enum-type-def))))

(s/def ::attribute-def
  (s/cat :attr-kw unnamespaced-keyword?
         :attr-type ::type-def
         :cardinality #{:cardinality-one :cardinality-many}
         :doc-string string?))
(s/def ::attributes
  (s/coll-of ::attribute-def :kind vector?))

(s/def ::attribute unnamespaced-keyword?)

(s/def ::parent unnamespaced-keyword?)
(s/def ::type #{:string :long :uuid :big-int :uri})
(s/def ::scope #{:context :global})
(s/def ::id
  (s/keys :req-un [::type ::attribute ::scope]))

(s/def ::entity-kind-def
  (s/keys :req-un [::id]
          :opt-un [::parent ::attributes]))

(s/def ::entity-kind-name unnamespaced-keyword?)
(s/def ::unify-schema
  (s/map-of ::entity-kind-name ::entity-kind-def))

(defn- strip-namespace
  [ns-kw]
  (keyword (name ns-kw)))

(defn validate! [schema-data]
  (when-not (s/valid? ::unify-schema schema-data)
    (let [error (s/explain-str ::unify-schema schema-data)]
      (throw (ex-info (str "Unify schema definition did not match spec!\n"
                           "Provide a map of entity kind keywords to defs, which must contain an id,"
                           "typically contain a vector of attributes, and possibly name a parent entity.\n"
                           "Attribute defs are a vector of: [attr-name attr-kind cardinality doc-string]\n"
                           "Spec failure:\n" error)
                      {:unify-schema-compilation/edn-spec-failure error})))))



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

(def rev-cardinality-lookup
  (map-invert cardinality-lookup))

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
  "Enrich kind data with parent if present, construct attributes maps,
  add any ref annotations to ref attributes"
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
    (write-schema (io/file schema-dir "metamodel.edn") metamodel-content)))

(defn kind-info->id-map
  [schema kind-info]
  (let [{:keys [unify.kind/global-id unify.kind/context-id]} kind-info]
    (if-not (or global-id context-id)
      {:attribute :unify.error/no-unique-id}
      (let [[scope attr-name] (if global-id
                                [:global (:db/ident global-id)]
                                [:context (:db/ident context-id :db/ident)])
            attr-map (first (filter #(= attr-name (:db/ident %)) schema))
            attr-type (-> attr-map :db/valueType :db/ident name keyword)
            doc (:db/doc attr-map)]
        (merge {:attribute (strip-namespace attr-name)
                :type attr-type
                :scope scope}
               (when doc {:doc doc}))))))

(defn enum?
  [schema attr]
  (and (= :db.type/ref (-> attr :db/valueType :db/ident))
       (not (:unify.ref/to attr))))

(defn unify-ref?
  [schema attr]
  (and (= :db.type/ref (-> attr :db/valueType :db/ident))
       (:unify.ref/to attr)))

(defn resolve-ref
  [schema attr]
  (:unify.ref/to attr))

(defn- ns-kw->ns-scope
  "Given a namespaced keyword, returns a new namespace scoped to the terminal name of the
   keyword, i.e. from :namespace/name to namespace.name
   e.g. :clinical-observation/rano -> clinical-observation.rano"
  [ns-kw]
  (let [kw-ns (namespace ns-kw)
        kw-name (name ns-kw)]
    (str kw-ns "." kw-name)))

(defn find-enums
  [schema attr]
  (let [enums (-> schema first :index/enum-idents)
        enum-ns (-> attr :db/ident ns-kw->ns-scope)
        matched-enums (filter #(= enum-ns (namespace %)) enums)]
    (if (seq matched-enums)
      (mapv (comp keyword name) matched-enums)
      [:unify.error/non-conforming-enums-or-missing-unify-ref])))

(defn resolve-attr-type
  [schema attr]
  (cond
    (enum? schema attr)
    {:enum-of (find-enums schema attr)}
    (unify-ref? schema attr)
    {:ref-to (resolve-ref schema attr)}
    :else
    (-> attr :db/valueType :db/ident name keyword)))

(defn datomic-attr->unify-attr
  [schema attr]
  (let [attr-name (-> attr :db/ident name keyword)
        attr-type (resolve-attr-type schema attr)
        cardinality (get rev-cardinality-lookup
                         (-> attr :db/cardinality :db/ident))
        doc-string (:db/doc attr)]
    [attr-name attr-type cardinality doc-string]))

(defn kind-info->attrs
  [schema kind-info {:keys [attribute] :as _id-map}]
  (let [kind (:unify.kind/name kind-info)
        schema-attrs (rest schema)
        matched-attrs (filter
                        (fn [attr-info]
                          (when-let [attr-name (:db/ident attr-info)]
                            (and (= kind (-> attr-name namespace keyword))
                                 ;; not a synthetic composite id
                                 (not= attr-name (metamodel/synthetic-attr? schema kind))
                                 ;; not a need-uid
                                 (not= attr-name (metamodel/need-uid? schema kind))
                                 ;; not the user specified unique id
                                 (not= attribute (strip-namespace attr-name)))))
                        schema-attrs)]
    (mapv (partial datomic-attr->unify-attr schema) matched-attrs)))

(defn infer-schema
  "Given a raw/post-compilation schema directory, attempts to infer a Unify schema."
  ([]
   (let [schema (schema/get-metamodel-and-schema)
         kinds (get-in schema [0 :index/kinds])]
     (into {}
           (for [[kind kind-info] kinds]
             (let [parent (:unify.kind/parent kind-info)
                   id-map (kind-info->id-map schema kind-info)
                   attr-vecs (kind-info->attrs schema kind-info id-map)]
               [kind (merge
                       {:id id-map}
                       (when parent
                         {:parent parent})
                       (when attr-vecs
                         {:attributes attr-vecs}))])))))
  ([schema-dir]
   (schema.cache/encache schema-dir)
   (infer-schema)))


(comment
  :troubleshooting
  ;; TODO: index/kind-attrs --- empty?
  ;; -- confirmed: let's remove it, I guess?
  (require '[clojure.pprint :as pp])
  (pp/pprint
    (infer-schema))

  (def example-schema-dsl
    (util.io/read-edn-file "test/resources/systems/patient-dashboard/schema/unify.edn"))
  (s/valid? ::unify-schema example-schema-dsl)
  (s/explain ::unify-schema example-schema-dsl))
