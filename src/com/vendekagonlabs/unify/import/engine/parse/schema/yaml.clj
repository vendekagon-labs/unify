(ns com.vendekagonlabs.unify.import.engine.parse.schema.yaml
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [com.vendekagonlabs.unify.util.io :as util.io])
  (:import (clojure.lang LazySeq)))

;; todo:

;; -- parse out keywords!

;; -- githublogin and ssh key for this machine, etc
;; -- get implementation to point that we can test config = config
;;    write template = this, or edn equivalent = this, etc.
;;    -- probably for multiple configs

(defn- massage-colls
  "Clj commons yaml parsing returns flatland ordered maps, but Unify
  doesn't care about preserving order. It also returns lazy seqs and
  lists where we prefer vecs or arraylists."
  [parsed-yaml]
  (walk/postwalk
    (fn [v]
      (cond
        (or (list? v)
            (instance? LazySeq v))
        (vec v)

        (map? v)
        (into {} (for [[key val] v]
                   [key val]))
        :else
        v))
    parsed-yaml))

(defn- fix-unify-variables
  "The :unify/variables inverts keyword expectation re: keys/vals position, so we
  fix this form manually. _Note_: this is universal for Unify schemas, not unique to
  any particular schema handling."
  [parsed-yaml]
  (walk/postwalk
    (fn [v]
      (if (and (map? v)
               (:unify/variables v))
        (assoc v :unify/variables
          (into {}
                (for [[key val] (:unify/variables v)]
                  [(name key) (keyword val)])))
        v))
    parsed-yaml))

(defn parse-keywords
  [parsed-yaml]
  (walk/postwalk
    (fn [v]
      (if (and (string? v)
               (str/starts-with? v ":"))
        (keyword (.substring ^String v 1))
        v))
    parsed-yaml))

(defn read-config-file
  [yaml-file-path]
  (-> yaml-file-path
      (slurp)
      (yaml/parse-string)
      (massage-colls)
      (fix-unify-variables)
      (parse-keywords)))

(defn fix-mapping-vars
  [parsed-mapping-yaml]
  (walk/postwalk
    (fn [v]
      (if-let [vars-map (:unify/variables v)]
        (assoc v :unify/variables
          (into {}
            (for [[key val] vars-map]
              [key (keyword val)])))
        v))
    parsed-mapping-yaml))

(defn read-mappings-file
  [yaml-file-path]
  (-> yaml-file-path
      (slurp)
      (yaml/parse-string)
      (massage-colls)
      (fix-mapping-vars)))

(comment
  (in-ns 'com.vendekagonlabs.unify.import.engine.parse.schema.yaml)
  (require '[clojure.pprint :as pp])
  (require '[clojure.data :refer [diff]])
  (require '[com.vendekagonlabs.unify.util.io :as util.io])


  (def config-file "test/resources/systems/candel/parse-config-examples/template-config.yaml")
  (def ref-file "test/resources/systems/candel/template-dataset/config.edn")
  (def ref-edn (util.io/read-edn-file ref-file))

  (def parsed-yaml (read-config-file config-file))
  (def diff-output (diff ref-edn parsed-yaml))
  (pp/pprint (first diff-output))
  (pp/pprint (second diff-output))

  (= ref-edn parsed-yaml)

  (def mapping-file "test/resources/systems/candel/parse-config-examples/template-mappings.yaml")
  (def ref-mapping-file "test/resources/systems/candel/template-dataset/mappings.edn")
  (def parsed-mapping (read-mappings-file mapping-file))
  (def ref-edn (util.io/read-edn-file ref-mapping-file))

  (pp/pprint parsed-mapping)

  (keys parsed-mapping)
  (keys ref-edn)
  (pp/pprint
    (first (diff ref-edn parsed-mapping)))
  (pp/pprint
    (second (diff ref-edn parsed-mapping)))
  (= parsed-mapping ref-edn)

  (keys (first diff-output))
  (pp/pprint
   (nth diff-output 2))

  (pp/pprint)

  (pp/pprint as-parsed)

  (def assays (get-in as-parsed [:dataset :assays]))
  (pp/pprint assays)
  (list? assays)
  (type assays))
