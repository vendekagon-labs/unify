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
(ns com.vendekagonlabs.unify.import.engine.parse.config
  (:require [clojure.data :as data]
            [contextual.core :as c]
            [clojure.walk :as walk]
            [clojure.set :as set]
            [com.vendekagonlabs.unify.util.collection :as coll]
            [com.vendekagonlabs.unify.db.metamodel :as metamodel]
            [com.vendekagonlabs.unify.util.text :as text]
            [com.vendekagonlabs.unify.util.timestamps :as timestamps]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [com.vendekagonlabs.unify.import.file-conventions :as conventions]
            [com.vendekagonlabs.unify.import.engine.parse.data :as parse.data]
            [com.vendekagonlabs.unify.db.util :as db.util]
            [com.vendekagonlabs.unify.util.release :as release]
            [com.vendekagonlabs.unify.db.schema :as schema]
            [clojure.string :as str]))

(def unify-key-whitelist #{:unify/constants :unify/value :unify/variables :unify/reverse :unify/precomputed
                           :unify/rev-variable :unify/input-file :unify/import :unify/variable
                           :unify/rev-attr :unify/many-delimiter :unify/many-variable
                           :unify/na :unify/omit-if-na
                           :unify.matrix/format :unify.matrix/input-file
                           :unify.matrix.format/sparse :unify.matrix.format/dense
                           :unify.matrix/column-attribute :unify.matrix/indexed-by
                           :unify.matrix/value-type :unify.matrix/constants})

(def req-top-keys #{:dataset :unify/import})

(defn strip-contextual
  "Strip out contextual by manually munging back into a normal config map. This is a gnarly
  way to do this, but the decontextualize function doesn't hit all cases."
  [nested-coll]
  (walk/postwalk (fn [node]
                   (cond
                     (vector? node)
                     (into [] node)

                     (map? node)
                     (into {} node)

                     :else
                     node))
                 nested-coll))

(defn kw-in-ns? [kw ns]
  (and (keyword? kw)
       (namespace kw)
       (str/starts-with? (namespace kw) ns)))

(defn in-unify-ns? [kw]
  (kw-in-ns? kw "unify"))

(defn in-glob-ns? [kw]
  (kw-in-ns? kw "glob"))

(defn- all-unify? [node]
  (every? in-unify-ns? (keys node)))

(defn- prune-nodes-by-key
  "Remove any nested maps in m that contain key"
  [m key]
  (walk/postwalk (fn [node]
                   (when-not (get node key)
                     node))
                 m))

(defn- flatten-nil-vecs
  "Replace [nil] with nil in nested map"
  [m]
  (walk/postwalk (fn [node]
                   (when-not (and (coll? node)
                                  (every? nil? node))
                     node))
                 m))

(defn- remove-nils
  "Return a copy of map m with keys that have nil as values removed"
  [m]
  (let [f (fn [x]
            (if (map? x)
              (let [kvs (filter (comp some? second) x)]
                (when-not (empty? kvs)
                  (into {} kvs)))
              x))]
    (walk/postwalk f m)))

(defn remove-directives
  "Remove any map in a nested map structure that contains the :unify/input-file
  or :unify.matrix/input-file keys"
  [config-map]
  (-> config-map
      ;; if we end up supporting more formats than tsv -> datoms and tsv -> s3,
      ;; we may want to make this pruning/separation more generic.
      (prune-nodes-by-key :unify/input-file)
      (prune-nodes-by-key :unify.matrix/input-file)
      flatten-nil-vecs
      remove-nils))

(defn only-directives
  "Return a map preserving only the nested structures that contain the :unify/input-file key"
  [config-map]
  (second (data/diff (remove-directives config-map) config-map)))


(defn- ns-if-not-ns
  "return key with the namespace 'ns' iff key is a keyword and has no namespace
  If key is not a keyword, return it unmodified"
  [key ns]
  (if (and (keyword? key)
           (not (namespace key)))
    (keyword ns (name key))
    key))

(defn- entity-keyword?
  "Predicate to include any x that is a keyword and not namespaced."
  [x]
  (and (keyword? x) (not (namespace x))))

(defn- ctx->ns
  "Transform keys in contextual map into namespaced keys according to namespace
  path rules defined in schema"
  [schema node]
  (if (or (not (map? node))
          (all-unify? node))
    node
    (let [ctx (c/context node)
          kw-ctx (filter entity-keyword? ctx)
          kind (metamodel/node-context->kind schema kw-ctx)]
      (into {:unify/node-kind kind}
            (for [[k v] node]
              [(ns-if-not-ns k (name kind)) v])))))



(defn namespace-config
  "Given a schema and contextual import config root, adds namespaces to all
  non-namespaced keys that reflect the kind of the entity map. Throws if config
  map fails validations."
  [schema ctx-cfg-root]
  (walk/postwalk (partial ctx->ns schema) ctx-cfg-root))


(defn ensure-raw [schema cfg-map]
  "Performs checks on a raw schema config map (not yet parsed into namespaces). Will throw
  if it encounters:
  - unify keywords not in whitelist
  - if top form is not a map
  - if top required keys are not all provided
  - if provided keys are not either required or allowable reference dataset kinds
  If no errors are provided, returns cfg-map unchanged."
  (let [kw-keys (coll/nested->keywords cfg-map)
        unify-keys (filter in-unify-ns? kw-keys)
        top-keys (keys cfg-map)
        var-attrs (set (mapcat vals (coll/find-all-nested-values cfg-map :unify/variables)))
        missing-top-keys (seq (set/difference (into #{} req-top-keys)
                                              (into #{} top-keys)))
        allowed-top-keys (set/union req-top-keys (into #{} (metamodel/allowed-ref-data schema)))
        invalid-top-keys (seq (filter (complement allowed-top-keys) top-keys))
        schema-attrs (into #{} (metamodel/attribute-idents schema))
        invalid-var-attrs (seq (set/difference var-attrs schema-attrs))
        invalid-unify-keys (seq (filter (complement unify-key-whitelist) unify-keys))
        invalid-map (when-not (map? cfg-map) (type cfg-map))
        error-map (cond-> {}
                    invalid-map (assoc :config-file/containing-form-not-map invalid-map)
                    invalid-unify-keys (assoc :config-file/invalid-unify-keys (vec invalid-unify-keys))
                    invalid-var-attrs (assoc :config-file/invalid-unify-variables-keys (vec invalid-var-attrs))
                    invalid-top-keys (assoc :config-file/invalid-top-keys (vec invalid-top-keys))
                    missing-top-keys (assoc :config-file/missing-top-keys (vec missing-top-keys)))]
    (if-not (seq error-map)
      cfg-map
      (throw (ex-info (str "Config was invalid:\n" (text/->unifyty-string error-map))
                      error-map)))))


(defn ensure-ns [schema ns-cfg-map]
  "Ensures all attributes are in the schema, requires namespaced config map. Throws if this
  condition is violated."
  (let [cfg-attrs (->> ns-cfg-map
                       (coll/nested->keyword-keys)
                       (remove in-unify-ns?)
                       (remove in-glob-ns?)
                       (into #{}))
        schema-attrs (into #{} (metamodel/attribute-idents schema))
        invalid-attributes (seq (set/difference cfg-attrs schema-attrs))]
    (if-not invalid-attributes
      ns-cfg-map
      (throw (ex-info (str "Invalid attributes specified in config: " invalid-attributes)
                      {:config-file/invalid-attributes invalid-attributes})))))


(defn- entity-map->attrs
  "Gets all attributes destined for entity map from directive. Note that
  this may be a flat attribute, {:some/attr v}, or could come from remapping
  specified by :unify/variables."
  [ent-map]
  (let [flat-attrs (filter keyword? (keys ent-map))
        ;; this actually is too permissive, accidentally non ns'd attrs
        ;; will fail. really we need to filter out keys of map before hand.
        vars (when-let [var-entry (:unify/variables ent-map)]
               (filter namespace (vals var-entry)))]
    (concat flat-attrs #_vars)))

(defn ensure-attr+entity
  "Ensure that every non-unify namespaced attribute in entity maps in config
  are namespaced with kind of entity for the node (invariant of unify schemas)."
  [ns-cfg-map]
  (walk/postwalk
    (fn [node]
      (if (or (not (map? node))
              (all-unify? node))
        node
        (let [kind (name (:unify/node-kind node))
              allowed-ns #{kind "unify" "unify.matrix" "glob"}
              attrs (entity-map->attrs node)
              ns-keys (set (map namespace attrs))]
          (if-let [wrong-attr (seq (set/difference ns-keys allowed-ns))]
            (throw
              (ex-info (str "Wrong attribute namespace: " wrong-attr " for entity: " kind)
                       {:config-file/entity-attribute-mismatch {:entity kind
                                                                :attrs wrong-attr}}))
            node))))
    ns-cfg-map))

(defn ensure-idents
  [schema ns-cfg-map]
  (when-let [wrong-keywords
             (seq
               (remove
                 (fn [kw]
                   (or (metamodel/enum-ident? schema kw)
                       (metamodel/attribute-ident? schema kw)
                       (metamodel/kind-by-name schema kw)
                       (in-unify-ns? kw)
                       (in-glob-ns? kw)))
                 (coll/nested->keywords ns-cfg-map)))]
    (throw (ex-info (str "The following keywords in the config map: "
                         (vec wrong-keywords)
                         " are not in the CANDEL schema.")
             {:config-file/bad-keywords (vec wrong-keywords)})))
  ns-cfg-map)


(defn resolve-literal-map-internal-refs
  "Node function that replaces internal references within the literal
  data map with appropriate lookup refs or entity maps per the metamodel."
  [schema root-ctx-cfg mapping node]
  ;; This is gross but necessary b/c of postwalk map-entry bug:
  ;; https://dev.clojure.org/jira/browse/CLJ-2031
  ;; The whole predicate should be
  ;; (and (map-entry? node) (metamodel-util/kind-ref? schema (key node)) and
  ;; first and second should be changed to key and val, respectively
  (if (and (vector? node) (= 2 (count node)) (not (coll? (second node)))
           (metamodel/kind-ref? schema (first node)))
    (do
      [(first node)
       (parse.data/resolve-value
         root-ctx-cfg
         schema
         mapping
         nil
         (first node)
         (second node))])
    node))


(defn- synthetic-uid
  "Returns a map of the synthetic UID associated with its target attribute
  name. The synthetic value is derived from the component attributes by
  concatinating their values (when available) in order."
  [schema parsed-cfg node synth-attr]
  (let [node-kind (:unify/node-kind node)
        components (metamodel/synthetic-attr-components schema node-kind)
        synthetic-value (->> components
                             (reduce
                               (fn [coll k]
                                 (if (contains? node k)
                                   (conj coll (get node k))
                                   coll))
                               [])
                             (clojure.string/join metamodel/synthetic-sep))]
    (if (clojure.string/blank? synthetic-value)
      (throw (ex-info (str "Could not synthesize an attribute for " node-kind)
                      {:engine/unsynthesized-attr {:synthetic-attribute synth-attr
                                                   :synthetic-components components
                                                   :node node
                                                   :from ::synthetic-uid}}))
      (parse.data/uid-attr-val schema parsed-cfg (assoc node synth-attr synthetic-value) {}))))

(defn- uid-config-node?
  "Does this node have configuration data that fully describes the attributes
   that are used to construct a UID?"
  [node]
  (let [context-type (last (:unify/ns-node-ctx node))]

    ;; skip :unify/input-file nodes
    ;; skip nodes whose context ends with
    ;;  :unify/reverse, :unify/constants, :unify/variables, :unify/input-file

    ;; I have no idea wtf this does, it doesn't skip these like it says, just sends them
    ;; to parse.data/uid-attr-val instead of synthetic-uid. Why? I also have no
    ;; idea.
    (not (or (contains? node :unify/input-file)
             (= context-type :unify/reverse)
             (= context-type :unify/constants)
             (= context-type :unify/variables)
             (= context-type :unify/input-file)))))

(defn- skip-uid?
  [node]
  (let [context-type (last (:unify/ns-node-ctx node))]
    (or (= context-type :unify.matrix/indexed-by)
        (= context-type :unify.matrix/constants))))

(defn add-uid
  "Given a schema, a root data structure, and a particular node (from the
  root data structure), determines if a node should have a UID, if so what
  attr name it needs, resolves the unique name from the root data structure,
  and returns the node with that attr name + unique name included."
  [schema parsed-cfg node]
  (let [node-kind (:unify/node-kind node)]
    (if-not (and (map? node)
                 (metamodel/need-uid? schema node-kind)
                 ;; kind of hacky but I'm not sure how else to get it not to try and calculate
                 ;; a uid inside a special syntax map.
                 (not (skip-uid? node)))
      node
      (let [synth-attr (metamodel/synthetic-attr? schema node-kind)
            uid-av (if (and synth-attr (uid-config-node? node))
                     (synthetic-uid schema parsed-cfg node synth-attr)
                     (parse.data/uid-attr-val schema parsed-cfg node {}))]
        (merge node uid-av)))))


(defn enrich-cfg-literal-data
  "Given a schema, mapping lookup, and a parsed config map, returns a map with UIDs
  added for all entities that require them, as per `add-uid` and resolves all literal
  references as per `resolve-literal-map-internal-refs`."
  [schema mapping parsed-cfg]
  (let [uids-added (clojure.walk/postwalk
                     (partial add-uid schema parsed-cfg)
                     parsed-cfg)
        refs-resolved (clojure.walk/postwalk
                        (partial resolve-literal-map-internal-refs schema parsed-cfg mapping)
                        uids-added)]
    refs-resolved))


(defn- path-to-parent
  [path]
  (->> path
       reverse
       (drop-while number?)
       rest
       reverse))

(defn add-parent-ref
  "Adds parent refs to directives (containing :unify/input-file) and specifies output file prefix
  to indicate that children/dependent data should be transacted after parent directives."
  [schema parsed-cfg node]
  ;; theoretically this could be name check now.
  (if-not (or (:unify/input-file node)
              (:unify.matrix/input-file node))
    node
    (if (:unify/reverse node)
      (assoc node :unify/out-file-prefix "01-priority-")
      ;; for forward ref case, we resolve forward ref with constant literal as lookup ref
      (let [ctx (:unify/ns-node-ctx node)
            fwd-ref-attr (last (filter keyword? ctx))
            par-kind (keyword (namespace fwd-ref-attr)) ;; todo - this should be done with metamodel-utils ?


            par-uid-attr (or (metamodel/need-uid? schema par-kind)
                             (metamodel/kind-context-id schema par-kind))

            par-uid-path (concat (path-to-parent ctx) [par-uid-attr])

            par-uid (get-in parsed-cfg par-uid-path)]

        ;; NOTE:
        ;; This currently only handles singly-nested input-file pair ordering
        ;; If multiple nesting support is required, dependency order resolution will have to be
        ;; more sophisticated
        (-> (assoc-in node [:unify/precomputed (db.util/reverse-ref fwd-ref-attr)] [par-uid-attr par-uid])
            (assoc :unify/out-file-prefix "00-priority-"))))))

(defn add-reverse-refs
  "Given a schema, an enriched literal data map, and a namespaced directive map, add the :unify/parent-ref
  map to all sub-maps with appropriate reverse ref data"
  [schema parsed-cfg parsed-cfg+uids]
  (clojure.walk/postwalk (partial add-parent-ref schema parsed-cfg) parsed-cfg+uids))


(defn add-node-contexts
  "Given a directive map, add node contexts (as per contextual) to each :unify/input-file node
  in either the :unify/node-ctx key or another passed key."
  ([m unify-key]
   (let [add-ctx-fn (fn [node]
                      (if (map? node)
                        (assoc node unify-key (c/context node))
                        node))]
     (clojure.walk/postwalk add-ctx-fn m)))
  ([m]
   (add-node-contexts m :unify/node-ctx)))


(def max-ref-data-cycles 100)

(defn order-ref-data-dependencies
  "Generates ordering based on ref-data dependencies."
  [ref-data-split]
  (loop [non-deps (vec (map :tmp/ref-attr (:no-deps ref-data-split)))
         deps (:deps ref-data-split)
         cycles 0]
    (if (or (empty? deps) (> cycles max-ref-data-cycles))
      (do
        (when (> cycles max-ref-data-cycles) (println :engine/order-ref-data-dependencies
                                                      "Max ref-data dependencies cycles ["
                                                      max-ref-data-cycles "]."
                                                      "This could mean there are circular references for ref-data"
                                                      "entities in the metadata."))
        (vec non-deps))
      (let [dep-attrs (into #{} (map :tmp/ref-attr deps))
            nds (reduce (fn [a dep-job]
                          (if (empty? (set/intersection dep-attrs (:tmp/ref-deps dep-job)))
                            (conj (or a []) (:tmp/ref-attr dep-job))
                            a))
                        nil
                        deps)
            ds (filter #(contains? (set/difference dep-attrs (into #{} nds)) (:tmp/ref-attr %))
                       deps)]
        (recur (vec (concat non-deps nds))
               ds
               (inc cycles))))))


(defn- format-ref-number [n]
  (str conventions/ref-file-prefix (format "%04d" n) "-"))

(defn ref-jobs->deps-order
  "Generate ref data dependencies and ordinality (if necessary)."
  [schema ref-data-jobs]
  (let [ref-jobs+deps (map (fn [m]
                             (assoc m :tmp/ref-deps (metamodel/ref-dependencies schema (first (:unify/node-ctx m)))
                                      :tmp/ref-attr (first (:unify/node-ctx m))))
                           ref-data-jobs)
        ref-deps-attrs (into #{} (map :tmp/ref-attr ref-jobs+deps))
        ref-split-jobs (group-by (fn [job-map]
                                   (let [ref-job-deps (:tmp/ref-deps job-map)]
                                     (if (empty? (set/intersection ref-deps-attrs ref-job-deps))
                                       :no-deps
                                       :deps)))
                                 ref-jobs+deps)
        ref-kinds-rev-order (->> (order-ref-data-dependencies ref-split-jobs)
                                 (dedupe)
                                 (reverse)
                                 (vec))
        ref-jobs-by-ref-kind (group-by :tmp/ref-attr ref-jobs+deps)
        prefix-map (into {} (map-indexed (fn [i ref]
                                           [ref (format-ref-number (inc i))])
                                         ref-kinds-rev-order))]
    (apply concat (for [[key job-maps] ref-jobs-by-ref-kind]
                    (map (fn [job-map]
                           (-> job-map
                             (assoc :unify/out-file-prefix (get prefix-map key))
                             ;; this is a band-aid on the misguided use of temp keys here to ensure that
                             ;; they don't leak out of ref job resolution.
                             (dissoc :tmp/ref-deps :tmp/ref-attr)))
                         job-maps)))))


(defn- maybe->absolute-path
  [rel-root-dir fname]
  (str (when (text/filename-relative? fname) rel-root-dir)
       fname))

(defn get-directive-maps
  "Returns a seq of directives (maps that contain :unify/input-file) from
  a (possibly multiply-nested) map"
  [cfg-dir m]
  (let [all-maps-list (coll/all-nested-maps m :unify/input-file)
        rm-fun (fn [[_ v]] (and (map? v) (:unify/input-file v)))]
    (->> all-maps-list
         (map #(into {} (remove rm-fun %)))
         ;; if we have multiple files from parsed glob pattern, then we
         ;; make a directive map for each.
         (mapcat (fn [d-map]
                   (let [input-file-spec (:unify/input-file d-map)]
                     (if-not (map? input-file-spec)
                       [(assoc d-map :unify/input-file (maybe->absolute-path cfg-dir input-file-spec))]
                       (let [{:keys [glob/directory glob/pattern]} input-file-spec
                             abs-dir (maybe->absolute-path cfg-dir directory)
                             matched-files (util.io/glob abs-dir pattern)
                             returned (mapv #(assoc d-map :unify/input-file %)
                                            matched-files)]
                         (if (seq returned)
                           returned
                           (throw
                             (ex-info
                               (str "Glob pattern didn't match any files: " directory " " pattern)
                               {:directive/glob-with-no-matches {:glob [directory pattern]}})))))))))))


(defn reference-data-jobs
  "Returns all directives for running reference data in order based on dependencies between
  reference data."
  [schema ref-only-cfg-map import-root-dir]
  (let [ctx-root (c/contextualize ref-only-cfg-map)
        ns-ref-data-maps (add-node-contexts
                           (c/contextualize
                             (zipmap (keys ctx-root)
                                     (map (partial namespace-config schema)
                                          (vals ctx-root)))))
        ref-jobs (get-directive-maps import-root-dir ns-ref-data-maps)
        ordered-ref-jobs (ref-jobs->deps-order schema ref-jobs)]
    ordered-ref-jobs))


(defn drop-unify-keys
  "Remove any keys prefixed with unify (convenience routine for dropping annotations
  from parsed config when necessary)."
  [nested]
  (walk/postwalk (fn [node]
                   (if (map? node)
                     (coll/remove-keys-by-ns node "unify")
                     node))
                 nested))

(defn drop-nil-elems
  "Drops nil elements from vectors that occur inside a nested data structure, as in the config
  or various processed verrsions of it.

  Hacky, but necessary to handle cases with mixed directives and literal records
  until a cleaner solution available."
  [m]
  (walk/postwalk (fn [v]
                   (if (and (coll? v)
                            (sequential? v))
                     (filterv some? v)
                     v))
                 m))

(defn cache-uid-prefix
  "For each node in directives, caches the parent UID prefix in that node map under
  the key :unify/parent-uid."
  [schema parsed-cfg+uids node]
  (let [parent-path (->> (:unify/ns-node-ctx node)
                         (reverse)
                         (drop-while number?)
                         (reverse)
                         (butlast))
        parent-node (get-in parsed-cfg+uids parent-path)]
    (if-let [parent-uid-attr (metamodel/need-uid? schema (:unify/node-kind parent-node))]
      (let [parent-uid (get parent-node parent-uid-attr)]
        (assoc node :unify/parent-uid parent-uid))
      node)))

(defn parse-config-map
  "Parses a config map, namespacing all keys, and decorating each node with kind and
  context information."
  [schema cfg-map]
  (->> cfg-map
       (ensure-raw schema)
       (c/contextualize)
       (:dataset)
       (namespace-config schema)
       (ensure-ns schema)
       (ensure-attr+entity)
       (hash-map :dataset)
       ;; this re-wraps in contextualize to get namespaced paths out
       (c/contextualize)
       (#(add-node-contexts % :unify/ns-node-ctx))
       (c/decontextualize)
       (strip-contextual)
       (ensure-idents schema)))


(defn cfg-map->dataset-entity
  "Given a schema, mapping lookup, and a parsed config map, return the data literal
  map for the :dataset (data about import/config, without jobs/directives)."
  [schema mapping parsed-cfg-map]
  (->> parsed-cfg-map
       (remove-directives)
       (enrich-cfg-literal-data schema mapping)
       (:dataset)
       (drop-unify-keys)
       (drop-nil-elems)))


(def molten-kws #{:unify/variables :unify/variable :unify/value})

(defn- verify-directives
  [directives-maps]
  (let [malformed-molten (keep (fn [m]
                                 (when (and (some molten-kws (keys m))
                                            (not-every? (set (keys m)) molten-kws))
                                   m))
                               directives-maps)]
    (if-not (seq malformed-molten)
      directives-maps
      (throw (ex-info (str "One of :" molten-kws
                           " set, but not all set (directive must specify all or none)")
               {:config/malformed-directive-maps malformed-molten})))))

(defn cfg-map->directives
  "Given a schema, a mapping lookup, the import-root-dir and a parsed config map,
  return the list of import job directives (with references resolved and full
  file paths constructed)."
  [schema mapping import-root-dir parsed-cfg-map]
  (let [cleaned-cfg-map (prune-nodes-by-key parsed-cfg-map :unify.matrix/input-file)
        parsed-cfg+uids (enrich-cfg-literal-data schema mapping cleaned-cfg-map)]
    (->> parsed-cfg-map
         (only-directives)
         ;; yes, this strip seems redundant but it removes the precomputed contextual
         ;; layer from the add-reverse-refs call and the last one doesn't, ¯\_(ツ)_/¯
         (strip-contextual)
         (add-reverse-refs schema parsed-cfg+uids)
         (get-directive-maps import-root-dir)
         (map (partial cache-uid-prefix schema parsed-cfg+uids))
         (verify-directives)
         (strip-contextual))))

(defn cfg-map->import-entity
  "From the config map, returns the data in the shape expected by database for inserting the
  import entity (literal data about import).

  Note: cfg-map in this arg is not parsed!"
  [cfg-map]
  (let [user (get-in cfg-map [:unify/import :user])
        dataset-name (get-in cfg-map [:dataset :name])
        import-name (str dataset-name "-" (timestamps/now))
        schema-version (schema/version)
        unify-version (release/version)]
    (when-not (and user import-name)
      (throw (ex-info (str "Config was invalid. Import entity must contain "
                           ":name, and :user keys."
                           "Note: if older config, you may need to change :import-name to :name")
                      {:config-file/invalid-unify-import-keys
                        {:keys-present (-> cfg-map :unify/import keys)
                         :keys-required #{:user :name :mappings}}})))
    {:import/user user
     :import/schema-version schema-version
     :import/unify-version unify-version
     :import/name import-name}))

(defn cfg-map->matrix-directives
  "Returns all matrix parsing directives specified in the import config, w/annotations in the map
  so that downstream data processing functions can do all necessary work to produce matrix
  entities and parse matrix files."
  [schema mapping import-root-dir parsed-cfg-map]
  (when-let [mtx-directives (coll/all-nested-maps parsed-cfg-map :unify.matrix/input-file)]
    (let [parsed-cfg+uids (enrich-cfg-literal-data schema mapping parsed-cfg-map)]
      (->> mtx-directives
           (strip-contextual)
           (add-reverse-refs schema parsed-cfg+uids)
           (map (fn [mtx-job]
                  (update-in mtx-job [:unify.matrix/input-file]
                             (partial maybe->absolute-path import-root-dir))))
           (strip-contextual)))))


(comment
  :schema-parsing-example-workflow

  (require '[com.vendekagonlabs.unify.util.io :as util.io])
  (def unify-schema (schema/get-metamodel-and-schema))
  ;; -- point to diferent root dir
  (def import-root-dir "/Users/bkamphaus/azure-datasets/template/")
  (def config-file (str import-root-dir "/config.edn"))
  (def config-edn (util.io/read-edn-file config-file))
  (def mapping (util.io/read-edn-file (str import-root-dir "/mappings.edn")))
  (def parsed-cfg-map (parse-config-map unify-schema config-edn))
  (def parsed-cfg+uids (enrich-cfg-literal-data unify-schema mapping parsed-cfg-map))
  (def ex-jobs
    (cfg-map->directives unify-schema mapping import-root-dir parsed-cfg-map))
  (keys (first ex-jobs))
  (map :unify/parent-uid ex-jobs))

(comment
  :test-parsing-matrix-config
  (require '[com.vendekagonlabs.unify.util.io :as util.io])
  (require '[clojure.pprint :as pp])
  (def unify-schema (schema/get-metamodel-and-schema))
  (def import-root-dir "/Users/bkamphaus/code/unify/test/resources/matrix/")
  (def config-file (str import-root-dir "/config.edn"))
  (def config-edn (util.io/read-edn-file config-file))
  (def mapping (util.io/read-edn-file (str import-root-dir "/mappings.edn")))
  (def parsed-cfg-map (parse-config-map unify-schema config-edn))
  (def dataset-entity (cfg-map->dataset-entity unify-schema mapping parsed-cfg-map))
  (pp/pprint dataset-entity)
  (def import-entity (cfg-map->import-entity config-edn))
  (pp/pprint parsed-cfg-map)
  (def matrix-directives (cfg-map->matrix-directives unify-schema mapping import-root-dir parsed-cfg-map))
  (pp/pprint matrix-directives)
  (count matrix-directives)
  (def directives (cfg-map->directives unify-schema mapping import-root-dir parsed-cfg-map))
  (pp/pprint directives))


