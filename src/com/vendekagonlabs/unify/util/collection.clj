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
(ns com.vendekagonlabs.unify.util.collection
  (:require [com.vendekagonlabs.unify.util.text :refer [match-ns]]))


(defn traversable? [node]
  (or (map? node)
      (sequential? node)
      (set? node)))

(defn nested->keywords
  "Given a nested map (vec and maps), returns all keywords in the map wherever they appear.
  NOTE: This finds all keywords, not just keywords that appear as map keys, at the moment."
  [nested-map]
  (filter keyword? (tree-seq
                     traversable?
                     (fn [n]
                       (cond
                         (map? n) (interleave (keys n) (vals n))
                         (sequential? n) (seq n)
                         (set? n) (seq n)))
                     nested-map)))

(defn nested->keyword-keys
  "Given a nested map (vec and maps), returns all keywords in the map that appear as keys."
  [nested-map]
  (filter keyword? (tree-seq
                     traversable?
                     (fn [n]
                       (cond
                         (map? n) (concat (keys n) (filter traversable? (vals n)))
                         (vector? n) (seq n)
                         (set? n) (seq n)))
                     nested-map)))

(defn filter-map
  "Filters a map m via function f and returns a map."
  [f m]
  (into {} (filter f m)))

(defn remove-keys-by-ns
  "Dissoc keys from map m that have namespace ns."
  [m ns]
  (filter-map #(not (match-ns ns (first %))) m))

(defn find-all-nested-values
  "finds all nested values for a given key `k` in map `m`"
  [m k]
  (->> m
       (tree-seq traversable?
                 (fn [node]
                   (if (map? node)
                     (vals node)
                     (seq node))))
       (filter map?)
       (keep k)))

(defn all-nested-maps
  "finds all nested values for a given key `k` in map `m`"
  [m k]
  (->> m
       (tree-seq traversable?
                 (fn [node]
                   (if (map? node)
                     (vals node)
                     (seq node))))
       (filter #(and (map? %) (get % k)))))

(defn csv-data->maps [csv-data]
  "Converts CSV data into a collection of maps with keys corresponding to the column headers"
  (let [hdr (first csv-data)
        data (rest csv-data)]
    (map zipmap (repeat hdr) data)))
