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
(ns com.vendekagonlabs.unify.db.indexes)

(defn- by-key
  [schema k]
  (into {} (keep (fn [ent]
                   (when-let [lookup-by (get ent k)]
                     [lookup-by ent]))
                 schema)))

(defn by-ident [schema]
  (by-key schema :db/ident))

(defn by-kind-name [schema]
  (by-key schema :unify.kind/name))

(defn by-kind-attr [schema]
  (->> (by-key schema :unify.kind/attr)
       (map #(vec [(:db/ident (first %)) (second %)]))
       (into {})))

(defn by-uid [schema]
  (->> (by-key schema :unify.kind/need-uid)
       (map #(vec [(:db/ident (first %)) (second %)]))
       (into {})))

(defn refs-by-ident [schema]
  (->> (by-ident schema)
       (filter (fn [[ident ent]]
                 (= :db.type/ref
                    (get-in ent [:db/valueType :db/ident]))))
       (into {})))

(defn card-many-by-ident [schema]
  (->> (by-ident schema)
       (filter (fn [[ident ent]]
                 (= :db.cardinality/many
                    (get-in ent [:db/cardinality :db/ident]))))
       (into {})))

(defn all [schema]
  {:index/idents (by-ident schema)
   :index/kinds (by-kind-name schema)
   :index/kind-attrs (by-kind-attr schema)
   :index/uids (by-uid schema)
   :index/refs (refs-by-ident schema)
   :index/card-many (card-many-by-ident schema)})

(comment
  (require '[com.vendekagonlabs.unify.db.schema :as db.schema])
  (def schema (db.schema/get-metamodel-and-schema))
  (card-many-by-ident schema))
