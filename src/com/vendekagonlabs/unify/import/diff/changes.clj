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
(ns com.vendekagonlabs.unify.import.diff.changes
  "This namespace focuses on the mechanics of comparing two datasets that
   share UID paths in their attributes. These two datasets exist in the same
   branch database via a transaction of a 'scratch' version of a dataset.
  i.e. a dataset whose name has been systematically altered when being
       transacted that contais the changes this namespace collects."
  (:require [clojure.data :as data]
            [clojure.pprint :refer [pprint]]
            [datomic.api :as d]
            [com.vendekagonlabs.unify.db.metamodel :as metamodel]
            [com.vendekagonlabs.unify.db :as db]
            [com.vendekagonlabs.unify.db.schema :as db.schema])
  (:import (datomic.query EntityMap)))

;; Schema cache -----------------------------------------------------
;;

(defonce schema (db.schema/get-metamodel-and-schema))


;; DB Connection Info Cache ----------------------------------------------
;;

(defonce db-atm (atom nil))

(defn cache-db-info! [info]
  (reset! db-atm info))

(defn latest-db []
  (db/latest-db @db-atm))

;; Lazy access by dataset and attribute -------------------------------
(defn paths-by-attr
  "For each datom with uid attribute key, retrieves ID path to entity
  referenced by that attribute"
  [dataset-name attr-key]
  (->> (d/datoms (latest-db) :avet attr-key)
       (keep (fn [[e a [dataset path]]]
               (when (= dataset-name dataset)
                 path)))))

;; Attribute Entity Data ----------------------------------------------
(defn entid->pathref
  "Converts an entity id map into path using the path of it's uid.
   i.e. #{12312312} -> [:sample/uid '/some/path']"
  [entid-map]
  (let [id (:db/id entid-map)
        ent-map (into {} (d/entity (latest-db) id))
        attrs (dissoc ent-map :db/id)
        ns (namespace (first (keys attrs)))
        uid (keyword ns "uid")]
    (cond
      (contains? attrs uid)
      {uid (second (get attrs uid))}

      (metamodel/ref-data? schema (keyword ns))
      (let [context-id (metamodel/kind-context-id schema (keyword ns))
            context-id-val (get ent-map context-id)]
        {context-id context-id-val})

      :else
      (throw (ex-info "Unresolved path reference in diff!"
                      {:uid uid
                       :entity-map ent-map})))))

(defn inflate-paths
  "Expands in-place any attribute that has a ref entity into it's path
   data. I.e. :measurement/same #db{id 1231232132} or :measurement-set #{ ... }"
  [m]
  (reduce
    (fn [coll [k v]]
      (cond
        (set? v)
        (assoc coll k (into #{} (map #(entid->pathref %) v)))

        (and (instance? EntityMap v))
        (assoc coll k (entid->pathref v))

        :else (assoc coll k v)))
    {}
    m))



;; TODO: dataset-name unused, possible to thread through and avoid later
;; pathref->reference inflation? (possibly not)
(defn attrs
  [dataset-name attr-key ref]
  (let [p [attr-key ref]
        ent (d/entity (latest-db) p)]
    ;; note: this returns `nil` unlike other uses of nonexistent db id
    ;; for d/entity because p is a ref id lookup
    (if (nil? ent)
        nil
      (-> (into {} ent)
          (dissoc :db/id)
          (dissoc attr-key)
          inflate-paths))))

;; Diff -------------------------------------------------------------
;;

(defn add-uid [dataset-name attr-key ref m]
  (assoc m attr-key ref))

(defn uid-ref [dataset-name attr-key ref]
  [attr-key ref])

(defn pathref->reference
  "Converts a 'pathref' in the form [:sample/uid '/some/path']
  into a Datomic reference: [:sample/uid ['dataset' '/some/path']"
  [dataset-name pathref]
  (let [k (first (keys pathref))
        entity-kind (keyword (namespace k))
        v (get pathref k)]
    (if (metamodel/ref-data? schema entity-kind)
      [k v]
      [k [dataset-name v]])))

(defn resolve-references
  "Converts a partial reference built via  entid->pathref
   into a properly formed tuple uid."
  [dataset-name m]
  (reduce
    (fn [coll [k v]]
      (cond
        (set? v)
        (assoc coll k (into #{} (map #(pathref->reference dataset-name %) v)))

        (and (map? v))
        (assoc coll k (pathref->reference dataset-name v))

        :else (assoc coll k v)))
    {}
    m))

(defn expand-set-values
  [base attr set-value]
  (mapv
    (fn [v]
      (merge base {:attr attr :value v}))
    set-value))

(defn diff-changes
  "Converts all alltered attributes in an entity into individual
   changes. Any attribute that has a set value are expanded."
  [dataset-name attr-key ref state changes]
  (when (some? changes)
    (let [resolved (->> changes
                        (resolve-references dataset-name))
          base {:state state
                :uid (uid-ref dataset-name attr-key ref)}
          filtered (filter #(not (set? (second %))) resolved)
          result (reduce
                   (fn [coll [k v]]
                     (conj coll (merge base {:attr k :value v})))
                   []
                   filtered)]
      (if-let [set-values (filter #(set? (second %)) resolved)]
        (reduce
          (fn [coll [k v]]
            (let [expanded (expand-set-values base k v)]
              (concat coll expanded)))
          result
          set-values)
        result))))


(defn cull-change-duplicates
  "Culls all removed entries that are due to changed
   attributes. The diff operation's 'removed' map
   includes the previous values that will be upserted."
  [changed removed]
  (let [culled (reduce
                 (fn [coll k]
                   (if (and (contains? coll k)
                            (not (metamodel/card-many? schema k)))
                     (dissoc coll k)
                     coll))
                 removed
                 (keys changed))]
    (if (= 0 (count (keys culled)))
      nil
      culled)))


(defn diff
  "Returns a seq of maps that indicate the :state change of the entity
   (:skip, :changed, :new :removed-attr) along with the attributes that have
   been modified."
  [{:keys [dataset-name diff-dataset-name]} attr-key ref-o ref-s]
  (let [o (attrs dataset-name attr-key ref-o)
        ;;_ (prn {:o o})
        s (attrs diff-dataset-name attr-key ref-s)]
        ;;_ (prn {:s s})]

    (if (nil? o)
      ;; New Entity returns the entire unpacked :entity
      {:state :new
       :entity (->> s
                    (add-uid dataset-name attr-key ref-o)
                    (resolve-references dataset-name))}

      (let [diff (data/diff s o)
            changed (first diff)
            removed (second diff)]
        (if (and (nil? changed) (nil? removed))

          ;; Skip anything that hasn't changed
          {:state :skip}

          ;; Changes all return :uid :attr :value keys
          ;;
          ;; NOTE: Filter out cases where a changed attribute value
          ;; also appears as a removed attribute. This happens
          ;; when an entity's attributes have been changed
          ;; (via adding or alteration).
          ;;
          ;; Only processing changed -or- removed can miss cases
          ;; when an entity has attributes removed -and- changed.
          ;;
          (let [culled-removed (cull-change-duplicates changed removed)]
            (-> []
                (concat (diff-changes
                          dataset-name attr-key ref-o :changed changed))
                (concat (diff-changes
                          dataset-name attr-key ref-o :removed culled-removed)))))))))


;; Main comparison fns ---------------------------------------------------
;;
(defn diff-by-attr [{:keys [dataset-name diff-dataset-name] :as dataset} attr-key]
  (let [paths (paths-by-attr diff-dataset-name attr-key)]
    (reduce
      (fn [coll path]
        (let [ref-o [dataset-name path]
              ref-s [diff-dataset-name path]
              diff (diff dataset attr-key ref-o ref-s)]
          (cond
            (seq? diff)
            (concat coll diff)

            (not (= :skip (:state diff)))
            (conj coll diff)

            :else coll)))

      []
      paths)))

(defn removed-entities-by-attr
  "Returns all entities that have been removed"
  [{:keys [dataset-name diff-dataset-name]} attr-key]
  (let [paths (paths-by-attr dataset-name attr-key)]
    (reduce
      (fn [coll path]
        (let [ref [attr-key [diff-dataset-name path]]
              e (d/entity (latest-db) ref)]
          (if (nil? e)
            (conj
              coll
              {:state :removed-entity
               :entity [attr-key [dataset-name path]]})
            coll)))
      []
      paths)))


(defn dataset-changes
  "Returns all entities that have been added to or removed from the dataset
  entity, as well as their attributes/values."
  [{:keys [dataset-name diff-dataset-name] :as dataset}]
  (let [diff (diff dataset :dataset/name dataset-name diff-dataset-name)]
    (cond
      (seq? diff)
      diff

      (not (= :skip (:state diff)))
      diff

      :else nil)))

