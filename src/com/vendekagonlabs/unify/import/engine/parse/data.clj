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
(ns com.vendekagonlabs.unify.import.engine.parse.data
  (:require [com.vendekagonlabs.unify.util.collection :as collection]
            [clojure.instant :refer [read-instant-date]]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [cognitect.anomalies :as anom]
            [com.vendekagonlabs.unify.db.util :as db.util]
            [com.vendekagonlabs.unify.util.text :as text]
            [com.vendekagonlabs.unify.db.metamodel :as metamodel]))

(set! *warn-on-reflection* true)

(s/def ::lookup-ref
  (s/and sequential?
         (s/cat :id-key keyword? :id-val string?)))

(s/def ::id-map
  (s/and
    (s/map-of keyword? string?)
    #(= 1 (count %))))

(s/def ::entity-reference
  (s/or :lookup-ref ::lookup-ref
        :id-map ::id-map))

(def string->datatype {:db.type/int     #(Long/parseLong %)
                       :db.type/long    #(Long/parseLong %)
                       :db.type/float   #(Double/parseDouble %)
                       :db.type/double  #(Double/parseDouble %)
                       :db.type/instant read-instant-date
                       :db.type/boolean (fn [^String s]
                                          (if (#{"true" "false"} (str/lower-case s))
                                            (Boolean/parseBoolean s)
                                            (throw
                                              ;; note: this exception gets wrapped in ex-info
                                              ;; by `parse-value`
                                              (Exception.
                                                (str "Boolean must be one of "
                                                     "{true, TRUE, false, FALSE}, not: "
                                                     s)))))
                       :db.type/ref     (fn parse-ref [^String s]
                                          (if (.startsWith s ":")
                                            (edn/read-string s)
                                            s))})


;; used in concatenating context IDs (scoped identifiers)
;; NOTE: this is an obscure ascii multi-character code intentionally, to avoid delimiter
;;       conflicts for context IDs which contain a composite identifier which may
;;       incidentally be a common delim, such as , ; / | etc
(def context-id-delim "|:~")

;; list of special characters that need to be escaped to be used as literals
;; in a regex
(def regex-escape
  #{"." "\\" "+" "*" "?" "[" "^" "]" "$" "(" ")" "{" "}" "=" "!" "<" ">" "|" ":" "-"})

(defn parse-value
  "Parses value with specific parsing rules for attributes and datatypes."
  [schema attribute dt v]
  (if-some [tf (get string->datatype dt)]
    (try
      (tf v)
      (catch Exception e
        (throw (ex-info (str "Exception: attribute '" attribute
                             "' with value '" v "' and datatype '" dt "'.")
                        {:data-file/parse-value {:attribute attribute
                                                 :datatype  dt
                                                 :value     v}}
                        e))))
    (if (metamodel/kind-by-name schema dt)
      [(metamodel/kind-context-id schema dt) v]
      v)))


(defn- ctx-leaf->ctx-id-lookup
  "Returns a lookup map of reference attrs to target context ids for the kind
   that reference targets."
  [ctx-node context-ids]
  (let [kw-only-ctx (filter keyword? (:unify/ns-node-ctx ctx-node))]
    (into {} (map vector kw-only-ctx context-ids))))

(defn- ctx-path->kinds
  "For each element of a contextual path, resolves that element to its kind
  name as specified in the schema metamodel."
  [schema ctx-path]
  (let [kw-only-ctx (filter keyword? ctx-path)]
    (loop [rem-ents kw-only-ctx
           done '()]
      (if (seq rem-ents)
        (let [resolved (metamodel/node-context->kind schema rem-ents)]
          (recur (butlast rem-ents) (conj done resolved)))
        done))))


(defn- ctx-kinds->context-ids
  "For each kind name in a collection, returns its context id as specified in
  the schema metamodel."
  [schema ctx-kinds]
  (map (partial metamodel/kind-context-id schema) ctx-kinds))

(defn- prepend
  "Only add context ID delimiter when adding a new name element."
  [pre s]
  #_(log/warn "DEBUG" :pre pre :s s)
  (str
    pre
    (if (or (str/starts-with? s context-id-delim)
            (not (seq s)))
      ""
      context-id-delim)
    s))


(defn resolve-uid
  "Returns a schema-specific globally unique id (UID) given a schema, contextualized
  root map, and a particular node (which can be found from the root map). The UID is
  constructed as a munged string from all parent context ids.

  context-id values for a segment of the contextual path can be optionally
  replaced via the replace-map argument. For example, :subject/id can appear in
  a node as 'subject.id' when used as a reverse reference. This can be replaced
  by it's actual value via a replace-map attribute: {'subject.id' 1232424}.
  This are currently only supplied by create-self-uid for entities which have
  reverse references like therapy."
  [schema parsed-cfg node replace-map]
  (let [node-ctx (:unify/ns-node-ctx node)
        kinds (ctx-path->kinds schema node-ctx)
        ;; Gets the context id (which is used as the proxy 'value' for the end of the UID)
        ;; for every value in the path
        ctx-ids (ctx-kinds->context-ids schema kinds)
        ;; Maps each node value to the attribute key
        ctx-id-lookup (ctx-leaf->ctx-id-lookup node ctx-ids)]
    (loop [curr-node-ctx-stack node-ctx
           this-ent node
           name-so-far ""]
      (let [next-stack (butlast curr-node-ctx-stack)
            next-ent (get-in parsed-cfg next-stack)

            ;; Grabs the last attribute name out of the path
            ;; then looks up the value from the lookup table that
            ;; maps context-id values to its keyword at this level of
            ;; the path stack.
            ctx-attr-name (->> curr-node-ctx-stack
                               (filter keyword?)
                               last
                               (get ctx-id-lookup))

            this-name (let [path-val (get this-ent ctx-attr-name)]
                        (if (contains? replace-map path-val)
                          (get replace-map path-val)
                          path-val))

            so-far+this-name (prepend this-name name-so-far)]

        ;; Don't add the dataset name (the last one in the stack)
        (if (and (seq next-stack) (> (count next-stack) 1))
          (recur
            next-stack
            next-ent
            so-far+this-name)

          ;; The UID Schema attribute is a Tuple that conforms to:
          ;; ["dataset-name" "remaining/path/val"]
          (let [dataset-name (get-in parsed-cfg [:dataset :dataset/name])
                final-name (if (str/starts-with? so-far+this-name context-id-delim)
                             (subs so-far+this-name (count context-id-delim))
                             so-far+this-name)]
            (when (clojure.string/starts-with? final-name dataset-name)
              (throw (ex-info (str "path starts with dataset name")
                              {:path final-name
                               :node node})))
            [dataset-name final-name]))))))


(defn- invalid-uid? [uid-val]
  ;; these conditions are all indicators that part or all of the
  ;; UID resolution failed.
  (let [v (second uid-val)]
    (or (not v)
        (= "" v)
        (str/starts-with? v context-id-delim)
        (str/includes? v (str context-id-delim context-id-delim))
        (str/ends-with? v context-id-delim))))

(defn uid-attr-val
  "Returns UID attribute and value given schema, ns-root-ctx-config, and node. For this
  function, `root-ctx-config` requires context of full map, that it be namespaced,
  but only value from dataset key."
  [schema parsed-cfg node replace-map]
  (let [node-kind (:unify/node-kind node)]
    ;; remove unify scoped map annotations, e.g. reverse attr, glob context, etc.
    (when-not (= "unify" (namespace (last (filter keyword? (:unify/ns-node-ctx node)))))
      (when-let [uid-attr (metamodel/need-uid? schema node-kind)]
        (let [resolved-uid (resolve-uid schema parsed-cfg node replace-map)]
          (if-not (invalid-uid? resolved-uid)
            {uid-attr resolved-uid}
            (throw (ex-info (str "Could not resolve UID for: " node)
                            {:engine/unresolved-uid {:node           node
                                                     :replace-map    replace-map
                                                     :entity-kind    node-kind
                                                     :bad-uid-result resolved-uid}}))))))))


;; This chases down the singular target attribute.
;;
;;   1. pulls the context-id attribute to be referenced
;;   2. Walks up the context tree inspecting each node
;;   3. Collects all nested maps that target the the context-id of the ref (via tree-seq operation)
;;   4. Once found, pulls out the UID value given its full context path
;;   5. If not found walks up a level
;;
(defn resolve-ref-uid-in-context-impl
  "Resolve UID by expanding context one (upward) tree expansion at a time from the leaf
  node."
  [parsed-cfg schema job target-kind value]
  (let [target-attr (metamodel/kind-context-id schema target-kind)]
    (loop [curr-path (:unify/ns-node-ctx job)]
      (let [ctx-tree (get-in parsed-cfg curr-path)]
        (if-let [target-kind-node (first (collection/all-nested-maps ctx-tree target-attr))]
          (let [target-kind-path (:unify/ns-node-ctx target-kind-node)
                ns-rel-target-node (get-in parsed-cfg target-kind-path)
                id-node (assoc ns-rel-target-node target-attr value)
                uid (uid-attr-val schema parsed-cfg id-node {})]

            (-> uid
                (vals)
                (first)
                (nth 1)))

          (let [rem-path (butlast curr-path)]
            (if-not (seq rem-path)
              (log/error "Consumed ref stack without finding referent while attempting to generate UID.")
              (recur rem-path))))))))

(def resolve-ref-uid-in-context
  (memoize resolve-ref-uid-in-context-impl))

(defn ref-uid
  "Generates the uid of the target-kind for a reference, using the parsed config
  map, the schema, the job map, target-kind for reference, and value which should be
  resolved to the UID of the reference."
  [parsed-cfg schema job target-kind value]
  (let [full-path (butlast (metamodel/family-tree-ids schema target-kind))]
    (cond
      ;; uid is globally unique and does not need context prefixes
      (not (seq full-path)) value

      ;; perf: short-circuit expanding node context if things have dataset as parent
      (< (count full-path) 2)
      value

      ;; uid will depend on maximally local context within which ref is resolved
      ;; in import config
      :else
      (resolve-ref-uid-in-context parsed-cfg schema job target-kind value))))


(defn resolve-ref-uid
  "Resolves reference uids."
  [parsed-cfg schema job target-kind value]
  (let [uid-attribute (metamodel/need-uid? schema target-kind)
        resolved-ref-val (ref-uid parsed-cfg schema job target-kind value)]
    (when-not resolved-ref-val
      (throw (ex-info "Could not resolve UID attribute and value."
                      {:config/ref-uid {:target-kind      target-kind
                                        :uid-attribute    uid-attribute
                                        :resolved-uid-val resolved-ref-val
                                        :raw-uid-value    value}})))
    (if (metamodel/ref-data? schema target-kind)
      [(metamodel/kind-context-id schema target-kind) resolved-ref-val]
      ;; Unbundles the constructed uid, repackage to conform to:
      ;; ["dataset-name" "path specifier"]
      (let [dataset-name (get-in parsed-cfg [:dataset :dataset/name])]
        {uid-attribute [dataset-name resolved-ref-val]}))))


(defn resolve-mapping-value
  "Resolve value by looking up its mapping by attribute in the mapping-lookup."
  [mapping attribute value]
  (if (contains? mapping attribute)
    ;; First branch of the if is when the attribute should be mapped
    ;; This can't be done with an if-let, because the mapping sometimes resolves to 'false'
    (let [mapped-val (get-in mapping [attribute value])]
      (if (nil? mapped-val)
        (throw (ex-info (str "Mapping expected but not resolved for attribute: " attribute
                             " with value: " value ".")
                        {:data-file/unresolved-mapping {:attribute attribute
                                                        :value     value}}))
        mapped-val))
    ;; attribute is not in the mapping, so return it unchanged:
    value))


(defn resolve-value
  "Resolves the value for a specific attribute given attribute name, raw value, schema,
  and context."
  ([parsed-cfg schema mapping job attribute-name raw-value]
   (resolve-value parsed-cfg schema mapping job attribute-name raw-value #{} false))
  ([parsed-cfg schema mapping job attribute-name raw-value na-val-set]
   (resolve-value parsed-cfg schema mapping job attribute-name raw-value na-val-set false))
  ([parsed-cfg schema mapping job attribute-name raw-value na-val-set rev-ref]
   (let [dt (metamodel/attr->db-type schema attribute-name)]
     (resolve-value parsed-cfg schema mapping job attribute-name raw-value dt na-val-set rev-ref)))
  ([parsed-cfg schema mapping job attribute-name raw-value dt na-val-set rev-ref]
   ;; only attempt to resolve a mapping value from the context of a job, mappings should
   ;; only apply to data coming from files that needs to be remapped, not literal data
   ;; in config. [if they are, this breaks literal timepoint refs]
   (let [resolved-value (if-not job
                          raw-value
                          (if (na-val-set raw-value)
                            :unify/missing-value
                            (resolve-mapping-value mapping attribute-name raw-value)))
         ref-dir (if rev-ref :unify.ref/from :unify.ref/to)]
     (if-let [ref (metamodel/kind-ref? schema attribute-name)]
       (if (= resolved-value :unify/missing-value)
         resolved-value
         (resolve-ref-uid parsed-cfg schema job (get ref ref-dir) resolved-value))
       (let [parsed-value (if-not (string? resolved-value)
                            resolved-value
                            (parse-value schema attribute-name dt resolved-value))]
         (if (and (string? parsed-value)
                  (metamodel/ref? schema attribute-name))
           (throw (ex-info (str "Mapping for attribute " attribute-name
                                " with value " raw-value " not resolved correctly."
                                "Expected enum keyword, not string value")
                           {:data-file/unmapped-enum {:attribute attribute-name
                                                      :value     raw-value}}))
           parsed-value))))))


(defn- reverse-ref-uid-map
  "For entities that will contain reverse-reference's in the contextual path
   that are part of the job. (i.e. therapy, etc) generate a mapping of it's
   parsed value to it's entity value used in the configuration file description
   of the directive."
  [job entity]
  (when-let [rev (:unify/reverse job)]
    (let [ent-attr (:unify/rev-variable rev)]
      {ent-attr (get entity ent-attr)})))


;; Instead of checking in multiple places if the node is synthetic or not, etc.
;; create-self-uid make one check at the beginning and dispatch to different functions.
;; Maybe grab this in a later refactor? Matrix bolted on for now b/c that's the simplest
;; modification, but it's not the simplest design for this code path (but there are
;; bigger hairballs to disentangle first).
(defn create-self-uid
  "Create a UID for this entity if this entity kind is :needs-uid in the
  metamodel from the raw data by de-referencing its column in the
  raw record data. For synthetic attributes, the column data is
  the context-id attribute key.

  Throws if a UID is needed but can't be resolved."
  [parsed-cfg schema job enriched-entity]
  ;; this when guard ignores reference data (which does not have this key)
  (when-let [node-ctx (:unify/ns-node-ctx job)]
    (let [node (get-in parsed-cfg node-ctx)
          node-kind (:unify/node-kind job)
          synthetic? (metamodel/synthetic-attr? schema node-kind)
          matrix? (metamodel/matrix-key-attr schema node-kind)
          cached-parent-uid (:unify/parent-uid job)]
      (when-let [uid-attr (metamodel/need-uid? schema node-kind)]
        (let [context-id-attr (metamodel/kind-context-id schema node-kind)
              attr-key (get job context-id-attr)
              raw-id-val (cond
                           ;; Synthetic attributes already have a well-defined context-id-attr
                           synthetic? (get enriched-entity context-id-attr)
                           ;; matrix -- this is basically a value -> value no-op, but keeps us
                           ;; from adding yet another branch in this already too-branchy fn.
                           matrix? (get enriched-entity context-id-attr)
                           :else
                           (get enriched-entity attr-key))
              uid-map (merge
                        node
                        {context-id-attr raw-id-val})
              resolved-uid (cond
                             ;; if UID is synthetic, use synthetic attribute to compute UID.
                             ;; uses cached parent if no reverse ref, or substitutes reverse
                             ;; ref data and recomputes UID if not.
                             synthetic?
                             (if-let [replace-map (reverse-ref-uid-map job enriched-entity)]
                               (uid-attr-val schema parsed-cfg uid-map replace-map)
                               {uid-attr [(first cached-parent-uid)
                                          (prepend (second cached-parent-uid) raw-id-val)]})

                             ;; for all other UIDs, if we have a cached parent UID, prepend it.
                             cached-parent-uid
                             {uid-attr [(first cached-parent-uid)
                                        (prepend (second cached-parent-uid) raw-id-val)]}

                             ;; if not, compute UID from schema context.
                             :else
                             (uid-attr-val schema parsed-cfg uid-map {}))]
          resolved-uid)))))



(defn create-reverse-reference
  "If this job includes a :unify/reverse key, generate the appropriate reverse reference"
  [parsed-cfg schema mapping job raw-entity na-val-set]
  (when (:unify/reverse job)
    (let [rev-attr (:unify/rev-attr (:unify/reverse job))
          raw-value (get raw-entity (:unify/rev-variable (:unify/reverse job)))
          resolved-value (resolve-value parsed-cfg schema mapping job rev-attr raw-value na-val-set true)]
      [(db.util/reverse-ref rev-attr) (first resolved-value)])))


(defn- tidy-entry?
  "Determines if a particular value is a tidy data att/val pair (ignores unify
  keywords, already resolved values)."
  [[k v]]
  (and
    (not (str/starts-with? (namespace k) "unify"))
    (string? v)))

(defn extract-tidy-data
  "Extracts tidy explicit attribute data for record given context, schema, job,
  and raw entity map."
  [parsed-cfg schema mapping job raw-entity na-val-set]
  (->> job
       (filter tidy-entry?)
       (map (fn extract-tidy-value [[attr col-name]]
              (let [raw-value (get raw-entity col-name)
                    resolved-value (resolve-value parsed-cfg
                                                  schema
                                                  mapping
                                                  job
                                                  attr
                                                  raw-value
                                                  na-val-set)]
                [attr resolved-value])))
       (into {})))

(defn tuple-entry?
  [[k v]]
  (and
    (not= "unify" (namespace k))
    (vector? v)))

(defn extract-tuple-data
  [parsed-cfg schema mapping job raw-entity na-val-set]
  (when-let [tuple-entries (seq (filter tuple-entry? job))]
    (->> tuple-entries
         (map (fn [[attr col-names]]
                (let [tuple-types (get-in schema [0 :index/idents attr :unify.ref/tuple-types])

                      raw-values (mapv #(get raw-entity %) col-names)
                      _ (when-not (= (count tuple-types)
                                     (count raw-values))
                          (throw (ex-info "Row did not provide the right number of values to form a tuple."
                                          {:engine/invalid-tuple-entry
                                           {:column-names     col-names
                                            :file             (:unify/input-tsv-file job)
                                            :candel-attribute attr
                                            :tuple-values     raw-values}})))
                      resolved-values (mapv (fn [rv dt]
                                              (resolve-value parsed-cfg
                                                             schema
                                                             mapping
                                                             job
                                                             attr
                                                             rv
                                                             dt
                                                             na-val-set
                                                             false))
                                            raw-values tuple-types)]
                  ;; b/c of custom mapv of resolve-value, must additionally
                  ;; check for card-many attributes which require additional
                  ;; level of nesting.
                  (if (metamodel/card-many? schema attr)
                    [attr [resolved-values]]
                    [attr resolved-values]))))
         (into {}))))

(defn- synthetic-column-mapping
  "Returns all keys from the job that map to columns of the raw entity
   data. This includes reverse reference columns."
  [job]
  (->> job
       seq
       (keep
         (fn [[k v]]
           (cond
             (tidy-entry? [k v]) [k v]
             (= :unify/reverse k) [(:unify/rev-attr v)
                                   (:unify/rev-variable v)])))
       (into {})))


(defn create-synthetic-attr
  "Returns a map of the synthetic attribute associated with its target attribute name.
   The synthetic value is derived from the component attributes
   by concatenating their values (when available) in order.

  The component attribute's raw values are obtained from the tidy data mapping which
  associates the target attribute with the column of the raw entity data."
  [parsed-cfg schema mapping job raw-entity]
  ;; This when guard ignores reference data (which does not have this key)
  (when-let [node-ctx (:unify/ns-node-ctx job)]
    (let [node (get-in parsed-cfg node-ctx)
          node-kind (:unify/node-kind node)]
      (when-let [synth-attr (metamodel/synthetic-attr? schema node-kind)]
        (let [mapped-cols (synthetic-column-mapping job)
              components (metamodel/synthetic-attr-components schema node-kind)
              synthetic-value (->> components
                                   (keep (fn [k]
                                           (get raw-entity (get mapped-cols k))))
                                   (clojure.string/join metamodel/synthetic-sep))]
          (if-not (clojure.string/blank? synthetic-value)
            {synth-attr synthetic-value}
            (throw (ex-info (str "Could not synthesize an attribute for " node-kind)
                            {:engine/unsynthesized-attr {:synthetic-attribute  synth-attr
                                                         :synthetic-components components
                                                         :column-mapping       mapped-cols
                                                         :raw-entity           raw-entity
                                                         :from                 ::create-synthetic-attr}}))))))))

(defn molten?
  "Determines if there is molten data in a job."
  [job]
  (:unify/variable job))

(defn extract-molten-data
  "Extracts molten data (if present) from record."
  [parsed-cfg schema mapping job raw-entity na-val-set]
  (when (molten? job)
    (let [column-name (get raw-entity (:unify/variable job))
          ;; manual get chain instead of get-in is perf optimization
          attribute (get (get job :unify/variables) column-name)
          value (get raw-entity (:unify/value job))]
      (when-not (and column-name attribute)
        (throw (ex-info (str "Specified column name or attribute mismatch between config and file: "
                             "column " column-name " to attribute name: " attribute)
                        {:data-file-or-config/unify-variable-name {:column-name     column-name
                                                                   :unify/variables (keys (:unify/variables job))}})))
      ;; drop value if that column is not present in record, as this means it was
      ;; filtered out by NA logic. Also no truthy check, as `false` could be potentially valid.
      (when-not (nil? value)
        {attribute (resolve-value parsed-cfg schema mapping job attribute value na-val-set)}))))


(defn extract-card-many-data
  "Extracts card many data when delimited in a single value in a record by a delimiter, and
  when unify directive in job specifies to do so."
  [parsed-cfg schema mapping job raw-entity na-val-set]
  (let [many-att-val-pairs (filter (fn [[k v]]
                                     (and (map? v)
                                          (:unify/many-delimiter v)))
                                   job)]
    (when (seq many-att-val-pairs)
      (->> (for [[attr {:keys [unify/many-delimiter
                               unify/many-variable]}] many-att-val-pairs]
             (when-let [pre-vals (get raw-entity many-variable)]
               (let [delim-regex (re-pattern (if (regex-escape many-delimiter)
                                               (str "\\" many-delimiter)
                                               many-delimiter))
                     vals (mapv #(resolve-value parsed-cfg schema mapping job attr % na-val-set)
                                (str/split pre-vals delim-regex))]
                 {attr vals})))
           (apply merge)))))


(defn extract-constant-data
  "Resolves constant data to constant values using `resolve-value` and returns as a
  map of attribute to value to be merged into the entity.."
  [{:keys [parsed-cfg schema mapping]} job na-val-set]
  (when-let [constant-data (or (:unify/constants job)
                               (:unify.matrix/constants job))]
    (->> (for [[attr raw-val] constant-data]
           [attr (resolve-value parsed-cfg schema mapping job attr raw-val na-val-set)])
         (into {}))))


(defn wrap-card-many-refs
  "For single assertions of card-many attributes, these need to be wrapped in a vector. This
  function nests those values when necessary (using metamodel to determine necessity)"
  [schema entity]
  (->> (for [[attr resolved-val] entity]
         (if (and (metamodel/card-many? schema attr)
                  (s/valid? ::entity-reference resolved-val))
           [attr [resolved-val]]
           [attr resolved-val]))
       (into {})))


(defn handle-na
  [final-entity req-attr]
  ; The flatten is required to handle references and cardinality-many situations
  (let [na-attrs (->> (filter #(some #{:unify/missing-value} (flatten [(% 1)])) final-entity)
                      (map first)
                      set)
        select-by (remove na-attrs (keys final-entity))
        entity-wo-na (select-keys final-entity select-by)]
    (if-not (seq req-attr)
      entity-wo-na
      (if (and (= req-attr '[*])
               (seq na-attrs))
        (do
          (log/debug "Omitting entity due to any [*] NA values.:" (:unify/annotations final-entity))
          :unify/omit)
        (if-let [missing (seq (filter na-attrs req-attr))]
          (do
            (log/debug "Omitting entity due to NA values for: " missing " from:" (:unify/annotations final-entity))
            :unify/omit)
          entity-wo-na)))))


(defn- reference-data-entity?
  [schema e]
  (let [kind (keyword (namespace (first (keys e))))]
    (metamodel/ref-data? schema kind)))


(defn record->entity
  "For each record from a file being processed as a job, performs all munging and remapping steps
  necessary to create a valid entity from that record."
  [{:keys [parsed-cfg schema mapping import-name]} job header record na-val-set import-ctx]
  (if (not= (count header)
            (count record))
    (merge {::anom/category           ::anom/incorrect
            :data-file/process-record ::header-record-mismatch}
           import-ctx)
    ;; nothing bound in this let should throw due to file content issues
    (let [;; start-time (System/nanoTime)  ; part of per-record perf logging
          raw-entity (zipmap header record)
          omit-on (:unify/omit-if-na job)
          {:keys [filename line-number]} import-ctx
          annotation {:unify/annotations {:unify.annotation/filename    (text/file->str filename)
                                          :unify.annotation/line-number line-number}}]

      ;; things in try block can throw for various reasons when attempting to process
      ;; the record, this exception handling will result in an anomaly on the channel
      ;; which will immediately terminate entire job with informative errors.
      (try
        (let [molten-data (extract-molten-data parsed-cfg schema mapping job raw-entity na-val-set)
              reverse-ref-data (create-reverse-reference parsed-cfg schema mapping job raw-entity na-val-set)
              precomputed (:unify/precomputed job)
              tidy-data (extract-tidy-data parsed-cfg schema mapping job raw-entity na-val-set)
              tuple-data (extract-tuple-data parsed-cfg schema mapping job raw-entity na-val-set)
              card-many-data (extract-card-many-data parsed-cfg schema mapping job raw-entity na-val-set)

              ;; Enrich the raw entity data with the synthesized attribute before
              ;; generating its UID
              synth-attr-data (create-synthetic-attr parsed-cfg schema mapping job raw-entity)
              enriched-entity-data (merge raw-entity synth-attr-data)
              self-uid-data (create-self-uid parsed-cfg schema job enriched-entity-data)

              base-entity (->> (merge molten-data tidy-data precomputed
                                      tuple-data
                                      reverse-ref-data card-many-data
                                      synth-attr-data self-uid-data)
                               (wrap-card-many-refs schema))

              ;; Reference-data is decorated with the :unify.import/most-recent
              ;; attribute. This allows validation to identify those entities
              ;; that are a part of an import and later use.
              import-metadata (if (reference-data-entity? schema base-entity)
                                {:unify.import/most-recent [:import/name import-name]}
                                {})]
          ;; uncomment this and start-time to log per-record nanosec record perf
          ;;(log/debug ::record (:unify/node-kind job) "computed in "
          ;;           (- (System/nanoTime) start-time) " nanosec."
          (-> base-entity
              (collection/remove-keys-by-ns "unify")
              (merge annotation)
              (merge import-metadata)
              (handle-na omit-on)))
        (catch Exception e
          (let [exdata (ex-data e)]
            (merge {::anom/category ::anom/incorrect}
                   exdata
                   (when-not exdata
                     {:message (.getMessage e)}))))))))
