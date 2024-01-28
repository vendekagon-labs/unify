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
(ns com.vendekagonlabs.unify.db.import-coordination
  (:require [datomic.api :as d]
            [com.vendekagonlabs.unify.db.query :as dq]))

(defn import-entity-txn-eid
  "Return the tx-id for the import entity defined by the job
  named import-name"
  [db import-name]
  (let [result (ffirst (dq/q+retry '[:find ?import
                                     :in $ ?import-name
                                     :where
                                     [?import :import/name ?import-name]]
                                   db import-name))]
    result))


(comment
  ;; for reference purposes at the moment, these two queries are slower than is practical
  ;; for large imports on database containing multiple imports already.
  (def tx-uuids-for-import
    '[:find ?uuid
      :in $ ?import-name
      :where
      [?import :import/name ?import-name]
      [?txn :import/import ?import]
      [?txn :import/tx-id ?uuid]])

  (def all-tx-uuids
    '[:find ?uuid
      :in $
      :where
      [?txn :import/tx-id ?uuid]]))

(def first-import-tx-q
  '[:find ?tx
    :in $ ?name
    :where
    [_ :import/name ?name ?tx]])

(def after-tx-q
  '[:find ?uuid
    :in $ ?start-tx
    :where
    [(> ?tx ?start-tx)]
    [?tx :import/tx-id ?uuid]])

(defn imported-uuids-q
  "Return all tx-ids for transactions put into the database after the start of
  the job named import-name"
  [db import-name]
  (let [first-import-tx (ffirst (dq/q+retry first-import-tx-q db import-name))]
    (dq/q+retry after-tx-q db first-import-tx)))

;; cache uuid set results so multiple calls return same results
(def successful-uuids
  (atom nil))

(defn successful-uuid-set
  "Return a set of all transaction UUIDs already in the database for the import
  defined by import-name"
  [db import-name {:keys [invalidate] :as opts}]
  (if-let [uuid-set (and (not invalidate)
                         @successful-uuids)]
    uuid-set
    (let [uuid-q-results (if-let [import-ent-uid
                                  (import-entity-txn-eid db import-name)]
                           (->> (imported-uuids-q db import-name)
                                (map first)
                                (into #{}))
                           #{})]
      (reset! successful-uuids uuid-q-results))))


(comment
  (def db-uri "datomic:ddb://us-east-1/cdel-test-tcga/my-new-db")
  (def conn (d/connect db-uri))
  (def db (d/db conn))
  (successful-uuid-set db "pici0025-import" {:invalidate false})
  (import-entity-txn-eid db "pici0025-import")
  (flatten (imported-uuids-q db "pici0025-import")))
