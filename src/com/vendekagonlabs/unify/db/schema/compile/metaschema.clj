(ns com.vendekagonlabs.unify.db.schema.compile.metaschema
  (:require [clojure.spec.alpha :as s]
            [com.vendekagonlabs.unify.db.schema :as schema]))

(defn unify-error-kw? [kw]
  (= "unify.error" (namespace kw)))

(defn ns-keyword? [kw]
  (and (keyword? kw)
       (namespace kw)))

(s/def ::unify-error-tuple
  (s/cat :error-kw unify-error-kw?
         :error-ref some?))

(s/def ::member-attribute
  (s/or :ns-keyword ns-keyword?
        :error ::unify-error-tuple))

;; note, can make opts-map match grammar if we ever generate anything
;; nontrivial here.
(s/def ::opts-map map?)

(s/def ::tables
  (s/map-of ::member-attribute ::opts-map))

(s/def ::joins
  (s/map-of ns-keyword? symbol?))

(s/def ::datomic-metaschema
  (s/keys :req-un [::tables
                   ::joins]))

(defn schema->tables
  [schema]
  (->> schema
       (first)
       :index/kinds
       (mapv (fn [[kind {:keys [unify.kind/need-uid
                                unify.kind/global-id]}]]
               (if-let [id (or need-uid global-id)]
                 [(:db/ident id) {}]
                 [[:unify.error/no-unique-id kind] {}])))
       (into {})))

(defn schema->joins
  [schema]
  (->> schema
       (rest)
       (keep (fn [{:keys [unify.ref/to
                          db/ident]}]
               (when (and ident to)
                 [ident (-> to name symbol)])))
       (into {})))


(defn generate
  ([schema]
   {:tables (schema->tables schema)
    :joins (schema->joins schema)})
  ([]
   (let [schema (schema/get-metamodel-and-schema)]
     (generate schema))))

(comment
  (require '[clojure.pprint :as pp])
  (def metaschema-ex (generate))
  (pp/pprint metaschema-ex)
  (s/valid? ::datomic-metaschema metaschema-ex)
  (s/explain ::datomic-metaschema metaschema-ex))
