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
(ns com.vendekagonlabs.unify.bootstrap.obo
  (:require [clojure.string :as string]))


(defn- process-string
  [s]
  (-> s
      (string/replace #"\"" "")
      string/trim))


(defn- skip-line?
  [s]
  (let [v (string/split s #":")]
    (some #{"format-version" "data-version" "subsetdef" "date" "saved-by"
            "auto-generated-by" "default-namespace"
            "synonymtypedef" "remark" "ontology" "property_value"} [(first v)])))



(defn term->map
  [s]
  (let [xf (comp
             (remove #(= % ""))
             (map #(let [v (string/split % #":")
                         k (keyword (first v))
                         value (if (= (count v) 2)
                                 (second v)
                                 (string/join ":" (rest v)))]
                     [k (process-string value)])))]
    (into {} xf s)))


(defn terms
  [lines]
  (lazy-seq
    (loop [v []
           ls lines]
      (if (seq ls)
        (if (not (skip-line? (first ls)))
          (if (re-find #"^\[" (first ls))
            (cons v (terms (rest ls)))
            (recur (conj v (first ls)) (rest ls)))
          (recur v (rest ls)))
        [v]))))
