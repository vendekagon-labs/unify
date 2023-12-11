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
(ns com.vendekagonlabs.unify.db.backend
  (:require [datomic.api :as d]
            [com.vendekagonlabs.unify.db.config :as db.config]))


(defn ddb-base-uri []
  (str "datomic:ddb://"
       (db.config/aws-region) "/"
       (db.config/ddb-table) "/"))

(defn db-base-uri []
  (or (db.config/base-uri)
      (ddb-base-uri)))

(defn request-db
  [database]
  (let [uri (str (db-base-uri) database)
        result (d/create-database uri)]
    (if result
      {:db-name  database
       :database database
       :uri      uri}
      {:error "Database already exists!"})))

(defn delete-db
  [database]
  (let [uri (str (db-base-uri) database)
        result (d/delete-database uri)]
    (if result
      {:success  true
       :db-name  database
       :database database
       :uri      uri}
      {:error "Database not deleted!"})))

(defn database-info
  "Retrieves the branch database's datomic uri.
  Returns the uri, {:error ...} or throws an exception if the user doesn't have
  access permissions to the branch database."
  [database]
  {:uri (str (db-base-uri) database)})

(defn list-dbs []
  (try
    (d/get-database-names (str (db-base-uri) "*"))
    (catch Exception e
      {:error (.getMessage e)})))



