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
(ns com.vendekagonlabs.unify.import.engine.parse.mapping
  (:require [com.vendekagonlabs.unify.util.io :as util.io]
            [com.vendekagonlabs.unify.db.metamodel :as metamodel]
            [com.vendekagonlabs.unify.db.schema :as schema]))

(defn reverse-unroll
  "For a map of mixed structure {key [item1 item2] key item3} returns
  {item1 key, item2 key, item3 key}."
  [r-map]
  (->> (seq r-map)
       (mapcat (fn [[k entry]]
                 (if (coll? entry)
                   (for [elem entry]
                     [elem k])
                   [[entry k]])))
       (into {})))

(defn validate-enums!
  [mappings-edn]
  (let [schema (schema/get-metamodel-and-schema)
        mapping-keys (-> mappings-edn :unify/mappings keys)
        errors (->> (for [key mapping-keys]
                      (when-let [mapping-target-keys
                                 (->> (get-in mappings-edn [:unify/mappings key])
                                      (keys)
                                      (filter keyword?))]
                        (keep (fn [k]
                                (when-not (metamodel/enum-ident? schema k)
                                  k))
                              mapping-target-keys)))
                    (keep not-empty)
                    (apply concat)
                    (vec))]
    (when (seq errors)
      (throw (ex-info (str "Enums: " errors " in mapping file not in the CANDEL schema.")
               {:mapping-file/enums errors})))))

(defn mappings-edn->lookup
  "Given EDN as read from the mappings file, returns a lookup map for which a value can
  be used to retrieve its mapping/substitution."
  [mappings-edn]
  (validate-enums! mappings-edn)
  (->> (for [[attr enum-name] (:unify/variables mappings-edn)]
         [attr (reverse-unroll (get (:unify/mappings mappings-edn) enum-name))])
       (into {})))

(comment
  (require '[com.vendekagonlabs.unify.util.io :as util.io])

  (def mfile
    "/Users/bkamphaus/azure-datasets/abida2019/mappings.edn")

  (def ex-mapping
    (util.io/read-edn-file mfile))
  (-> ex-mapping mfile)
  (mappings-edn->lookup ex-mapping))
