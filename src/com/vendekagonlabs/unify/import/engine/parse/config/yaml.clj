(ns com.vendekagonlabs.unify.import.engine.parse.config.yaml
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (clojure.lang LazySeq)))

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

(defn- parse-raw-attr-name
  "From a string representation of a keyword attr, parses the same
  with or without a leading :"
  [s-attr-name]
  (if (str/starts-with? s-attr-name ":")
    (keyword (subs s-attr-name 1))
    (keyword s-attr-name)))

(defn- fix-unify-matrix-indexing
  "The :unify.matrix/indexed-by syntax inverts keyword expectation
  re: keys/vals position, as with unify/variables, so we fix this
  form manually."
  [parsed-yaml]
  (walk/postwalk
    (fn [v]
      (if (and (map? v)
               (:unify.matrix/indexed-by v))
        (assoc v :unify.matrix/indexed-by
                 (into {}
                       (for [[key attr-name] (:unify.matrix/indexed-by v)]
                           [(name key) (parse-raw-attr-name attr-name)])))
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
      (fix-unify-matrix-indexing)
      (parse-keywords)))

(defn fix-mapping-vars
  [parsed-mapping-yaml]
  (walk/postwalk
    (fn [v]
      (if-let [vars-map (:unify/variables v)]
        (assoc v :unify/variables
                 (into {}
                   (for [[key val] vars-map]
                     [key (parse-raw-attr-name val)])))
        v))
    parsed-mapping-yaml))

(defn read-mappings-file
  [yaml-file-path]
  (-> yaml-file-path
      (slurp)
      (yaml/parse-string)
      (massage-colls)
      (fix-mapping-vars)))
