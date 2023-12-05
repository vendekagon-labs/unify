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
(ns com.vendekagonlabs.unify.db.schema.cache
  (:require [com.vendekagonlabs.unify.db.schema :as schema]
            [com.vendekagonlabs.unify.db :as db]
            [com.vendekagonlabs.unify.util.uuid :as uuid]
            [datomic.api :as d]))

(defn temp-uri []
  (str "datomic:mem://" (uuid/random)))

(defn update-cached-schema
  []
  (let [db-uri (temp-uri)
        _ (db/init db-uri :skip-bootstrap true)
        conn (d/connect db-uri)
        db (d/db conn)
        updated-schema (schema/get-metamodel-and-schema db)]
    (schema/cache updated-schema)))

(defn -main [& args]
  (println "Re-caching schema.")
  (update-cached-schema)
  (println "Schema caching completed.")
  (System/exit 0))

(comment
  (-main))
