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
(ns com.vendekagonlabs.unify.validation.post-import.util
  "Contains logic for running validations depended on by spec and uid
  validations, as well as logic for formatting entities for reporting to
  user on failed validation."
  (:require [clojure.set :as set]
            [clojure.spec-alpha2 :as s]
            [clojure.walk :as walk]
            [com.vendekagonlabs.unify.validation.specs :as specs]
            [datomic.api :as d]
            [com.vendekagonlabs.unify.db :as db]
            [com.vendekagonlabs.unify.validation.post-import.query :as vquery]
            [com.vendekagonlabs.unify.db.schema :as db.schema]))

;; Output Formatting ------------------------------------------------------
;;

(defn- get-annotations-for-entity
  "Get unify.annotation information for an entity e (in map form). Returns
  a vector of two elements: the filename from which this entity was derived
  and the corresponding line number"
  [db e]
  (let [eid (:db/id e)]
    (d/q '[:find [?filename ?line-number]
           :in $ ?eid
           :where
           [?a :unify.annotation/entity ?eid]
           [?a :unify.annotation/filename ?filename]
           [?a :unify.annotation/line-number ?line-number]]
         db eid)))

(defn- get-referencing-entities
  "Returns a lazy-seq of entities that are referencing entity e (in map form)"
  [db e]
  (let [eid (:db/id e)
        xf (comp
             (map :e)
             (map (partial d/pull db '[*])))]
    (->> (d/datoms db :vaet eid)
         (sequence xf))))

(defn format-entity
  "Given a db and an entity e in map form, returns a formatted string for printing"
  [db e]
  (let [annot (get-annotations-for-entity db e)]
    (if annot
      (str e " ->  file:" (annot 0) " line:" (annot 1))
      (let [referencing-entities (take 10 (get-referencing-entities db e))
            out-str (str e " -> Referenced from (only first 10 entities are shown):")]
        (-> (reduce #(str %1 "\n\t" (format-entity db %2)) out-str referencing-entities)
            (str "\n"))))))


;; Entity Retrieval  ------------------------------------------------------
;;

(def pull-form '[* {:genomic-coordinate/assembly [*]}
                   {:variant/genomic-coordinates
                    [* {:genomic-coordinate/assembly [*]}]}
                   {:measurement/cnv-call [:db/ident]}
                   {:measurement/msi-status [:db/ident]}])

(defn hydrate-entity
  [db e-id]
  (let [pulled (d/pull db pull-form e-id)]
        ;; this lifts enums up to the level of idents
    (walk/postwalk (fn [m]
                     (if (and (map? m)
                              (contains? m :db/ident))
                       (:db/ident m)
                       m))
                   pulled)))

(defn uid-entities-by-attr
  "Returns all entities that have a uid attribute in
   the dataset."
  [db-info dataset-name entity-attr]
  (let [db (db/latest-db db-info)
        xf (comp
             (keep (fn [[e a [dname]]]
                     (when (= dname dataset-name)
                       e)))
             (map (partial hydrate-entity db)))]
    (sequence
      xf
      (d/datoms db :aevt entity-attr))))


(defn reference-data-entities
  "Returns all entities that are :kind/ref-data true."
  [db import-name]
  (let [xf (comp
             (map :e)
             (map #(d/pull db pull-form %)))]
    (sequence
      xf
      (d/datoms db :vaet [:import/name import-name] :unify.import/most-recent))))

(defn dataset-entity
  "Returns the dataset entity with the given name."
  [db-info dataset-name]
  (let [db (db/latest-db db-info)]
    (d/pull db '[*] [:dataset/name dataset-name])))

(defn uid-keys [db-info]
  (let [db (db/latest-db db-info)
        metamodel (db.schema/get-metamodel-and-schema db)]
    (keys (:index/uids (first metamodel)))))

(defn- lazy-cat-2
  [seqs]
  (lazy-seq
    (if (seq seqs)
      (concat (first seqs)
              (lazy-cat-2 (rest seqs))))))

(defn dataset->all-entities
  [db-info dataset-name]
  (let [uid-keys (uid-keys db-info)]
    (lazy-cat-2 (for [uid-key uid-keys]
                  (uid-entities-by-attr db-info dataset-name uid-key)))))

(defn- get-uid-from-entity
  [metamodel e]
  (let [uid-index (:index/uids (first metamodel))
        uid-attr (first (set/intersection (set (keys e))
                                          (set (keys uid-index))))]
    (get e uid-attr)))

(defn entity-has-valid-uid?
  "Uses metamodel information (as returned from db-schema/get-uid-from-entity) to
  determine whether the entity m in the db has a valid uid. Returns nil if the entity
  is valid, and an error message otherwise

  Valid UID criteria:

  1. there is a /uid attribute that matches the :kind/need-uid
  2. it has the correct format [\"dataset-name\" \"path/identifier\"]  "
  [metamodel dataset-name db e]
  (let [uid (get-uid-from-entity metamodel e)]
    (if-not (and (some? uid)
                 (coll? uid)
                 (= 2 (count uid))
                 (= dataset-name (first uid)))
      (merge-with concat
        e
        {:unify.validation/errors [(str "---- "(format-entity db e))]})
      e)))

(defn dangling-ref?
  "If an entity does not have attributes other than UID, it was created by reference only."
  [db metamodel entity]
  (let [e (dissoc entity :db/id :unify.validation/errors)
        uid-index (:index/uids (first metamodel))
        uid-attr (first (set/intersection (set (keys e))
                                          (set (keys uid-index))))
        e-keys (keys e)
        other-keys (set/difference (set e-keys) #{uid-attr})]
    (if (seq other-keys)
      entity
      (merge-with concat entity
                  {:unify.validation/errors [(str "---- The entity " (format-entity db entity)
                                                  " was referenced from somewhere but not defined anywhere.")]}))))

(defn attr-namespaces-match?
  "If non unify/db attribute namespaces don't match, this is a mistake."
  [db entity]
  (let [e (dissoc entity :db/id :unify.validation/errors)
        attr-keys (keys e)
        kind (-> attr-keys first namespace)
        all-ns (map namespace attr-keys)]
    (if (every? #(= kind %) all-ns)
      entity
      (merge-with concat entity
                  {:unify.validation/errors [(str "---- The entity " (format-entity db entity)
                                                  " contains attributes that do not belong on the same entity.")]}))))


(defn- spec-validation
  "Validates an entity in map form, using spec-based validations. Returns nil
  if the entity is valid, and an error message otherwise"
  [db entity]
  (let [e (dissoc entity :db/id :unify.validation/errors)
        kind (-> e keys first namespace)
        spec (keyword specs/namespace-name kind)]
    (if (and
          (not= kind "unify.annotation") ; Skip annotation entities
          (not (s/valid? spec (dissoc e :unify.validation/errors)))) ;; Skip previous errors
      (merge-with concat entity
        {:unify.validation/errors
         [(str "---- The entity " (format-entity db entity) " failed the following specs:\n"
               (s/explain-str spec e))]})
      e)))

(defn ref-data-validations
  "Sequentially iterates over all reference data validations."
  [db-info]
  (let [db (db/latest-db db-info)
        last-import (-> db (db/ordered-imports) first :import-name)]
    (sequence (map (partial spec-validation db))
              (reference-data-entities db last-import))))

(defn data-validations
  "Sequentially iterates over all non-reference, dataset entities, applying all relevant
  validations."
  [db-info dataset-name]
  (let [db (db/latest-db db-info)
        metamodel (db.schema/get-metamodel-and-schema db)]
    (sequence (comp
                (map (partial entity-has-valid-uid? metamodel dataset-name db))
                (map (partial attr-namespaces-match? db))
                (map (partial dangling-ref? db metamodel))
                (map (partial spec-validation db))
                (map (partial vquery/validate-by-query db)))
              (dataset->all-entities db-info dataset-name))))
