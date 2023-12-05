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
(ns user
  (:require [datomic.api :as d]
            [clojure.data.csv :as csv]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [com.vendekagonlabs.unify.db.backend :as backend]
            [com.vendekagonlabs.unify.cli :as cli]
            [com.vendekagonlabs.unify.cli.error-handling :as err]))

(defn read-csv [csv-file]
  (with-open [reader (io/reader csv-file)]
    (doall
     (csv/read-csv reader))))

(defn make-csv
  "Dev helper fn for making CSVs, especially for query results."
  [fname cols tuples]
  (with-open [writer (io/writer fname)]
    ;; col names
    (csv/write-csv writer [cols])
    ;; rest of csv
    (csv/write-csv writer tuples)))


(defn unify [& args]
  (with-redefs [backend/db-base-uri
                (fn [] "datomic:dev://localhost:4334/")
                err/exit
                (fn [code msg]
                  (println "Would have exited with code" code
                           "and message:\n" msg))]
    (apply cli/-main args)))

(comment
  :unify-cli
  (def db-name "unify-test")
  (def working-dir "/Users/bkamphaus/scratch/unify-test")
  (def import-config "/Users/bkamphaus/code/unify-test-import/config.edn")

  (unify "delete-db" "--database" db-name)
  (unify "request-db" "--database" db-name)
  (unify "prepare" "--import-config" import-config
                  "--working-directory" working-dir)
  (unify "transact" "--working-directory" working-dir
                   "--database" db-name))

(comment
  :query
  (def db-uri "datomic:mem://test")
  (def conn (d/connect db-uri))
  (def db (d/db conn))

  (def gene-names
    (d/q '[:find ?sym (pull ?g [:gene/ensembl-id
                                :gene/hgnc-prev-symbols
                                :gene/alias-hgnc-symbols])
           :in $
           :where
           [?g :gene/hgnc-symbol ?sym]]
         db))

  ;; first we make a table containing all alias info, embedded in the table
  ;; as a json string.
  (def gene-table
    (map (fn [[gene-sym alias-map]]
           [gene-sym (json/json-str alias-map)])
         gene-names))

  (make-csv "gene_names.csv" ["hgnc_symbol" "other_names"] gene-table)

  (def ensembl->hgnc
    (d/q '[:find ?ensembl-id ?hgnc-sym
           :in $
           :where
           [?g :gene/hgnc-symbol ?hgnc-sym]
           [?g :gene/ensembl-id ?ensembl-id]]
         db))
  (take 5 ensembl->hgnc)

  (make-csv "ensembl2hgnc.csv" ["ensembl_id" "hgnc_symbol"] ensembl->hgnc)


  (d/q '[:find (count ?m)
         :in $
         :where
         [?m :measurement/fpkm]]
       db))

