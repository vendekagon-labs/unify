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
(ns com.vendekagonlabs.unify.db.gc-storage-call
  (:require [datomic.api :as d]
            [com.vendekagonlabs.unify.db.config :as config]))

(def db-list
  (d/get-database-names (str (config/base-uri) '*)))


(defn -main [& _args]
  (doseq [db db-list]
    (let [uri (str root-db-uri db)
          conn (d/connect uri)]
      (d/gc-storage conn (java.util.Date.)))))

(comment
  (-main))

