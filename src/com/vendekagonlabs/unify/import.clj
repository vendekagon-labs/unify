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
(ns com.vendekagonlabs.unify.import
  (:require [com.vendekagonlabs.unify.util.io :as util.io]
            [cognitect.anomalies :as anomalies]
            [clojure.tools.logging :as log]
            [com.vendekagonlabs.unify.db :as db]
            [com.vendekagonlabs.unify.db.schema :as db.schema]
            [com.vendekagonlabs.unify.db.schema.cache :as schema.cache]
            [com.vendekagonlabs.unify.import.diff.tx-data :as diff]
            [com.vendekagonlabs.unify.import.tx-data :as tx-data]
            [com.vendekagonlabs.unify.import.engine :as engine]
            [com.vendekagonlabs.unify.util.uuid :as uuid]
            [com.vendekagonlabs.unify.import.upsert-coordination :as upsert-coord]
            [com.vendekagonlabs.unify.import.file-conventions :as conventions]
            [com.vendekagonlabs.unify.util.text :refer [->pretty-string folder-of]]
            [com.vendekagonlabs.unify.validation.post-import :as post-import]))

(defn validate
  [{:keys [working-directory
           database] :as _args}]
  (let [dataset-name (conventions/dataset-name working-directory)
        db-info (db/fetch-info database)]
    (post-import/run-all-validations db-info dataset-name)))


(defn prepare-import
  "Create the txn data files from an import-config-file, datomic-config, and target-dir."
  [{:keys [target-dir
           import-cfg-file
           schema-directory
           tx-batch-size
           resume
           continue-on-error]}]
  (schema.cache/encache schema-directory)
  (let [import-config (util.io/read-edn-file import-cfg-file)
        config-root-dir (str (folder-of import-cfg-file) "/")
        schema (db.schema/get-metamodel-and-schema)
        import-result (engine/create-entity-data schema
                                                 import-config
                                                 config-root-dir
                                                 target-dir
                                                 resume
                                                 continue-on-error)
        txn-data-pre-process (tx-data/make-transaction-data! target-dir
                                                             tx-batch-size)]
    (log/info (str "Data files prepared: \n" (->pretty-string (map #(get-in % [:job :unify/input-file]) import-result))
                   "TX data prepared: \n " (->pretty-string txn-data-pre-process)))
    (if-let [errors (seq (filter ::anomalies/category import-result))]
      {:errors errors}
      import-result)))

(defn transact-import
  "Process txn data files into Datomic from target-dir, datomic-config, and tx-batch-size.
  If update is true then diff transaction files are used during this import."
  [{:keys [target-dir
           database
           datomic-uri
           resume
           skip-annotations
           disable-remote-calls
           schema-directory
           update
           diff-suffix]}]
  (println "Transacting prepared tx-data from directory:\n" target-dir "\ninto datomic db at:\n" datomic-uri)
  (let [ensured-datomic-config (db/ensure-db schema-directory datomic-uri)
        tx-result-map (tx-data/transact-import-data! target-dir
                                                     ensured-datomic-config
                                                     ;; TODO this shouldn't be
                                                     ;; linked to available processors, this is
                                                     ;; an io bound operation, isn't it?
                                                     ;; does this value even get respected?
                                                     (+ 2 (.. Runtime
                                                              getRuntime
                                                              availableProcessors))
                                                     {:resume resume
                                                      :skip-annotations skip-annotations
                                                      :disable-remote-calls disable-remote-calls
                                                      :update update
                                                      :diff-suffix diff-suffix})
        {:keys [ref-results data-results]} tx-result-map]
    (if-let [anomalies (seq (concat (filter ::anomalies/category ref-results)
                                    (filter ::anomalies/category data-results)))]
      (do (log/error "Import did not complete successfully (see logs for full report): "
                     (->pretty-string anomalies))
          {:errors anomalies})
      {:results (apply merge-with + (concat ref-results data-results))})))


(defn perform-diff
  "Performs the update operation:
   1. prepared data is transacted to branch with temp dataset uids
   2. Diff transactions are generated"
  ;; TODO: since very few of these keys get used, is that the intention? or is
  ;; something miswired? Do we even care for diff functionality?
  [{:keys [target-dir
           database] :as ctx}]
  (try
    (let [diff-tx-dir (conventions/diff-tx-dir target-dir)
          suffix (uuid/random-partial)
          diff-opts (assoc ctx :diff-suffix suffix)]

      ;; Remove any previous diff
      (conventions/rm-edn-files diff-tx-dir)

      ;; Write out the diff summary which includes the current HEAD
      ;; before transacting.
      ;;
      (log/info "Writing-summary " diff-opts)
      (diff/write-summary! diff-opts)

      (log/info "Perform Diff started with: " database " " target-dir
                " - transacting.")
      (transact-import diff-opts)

      (log/info "Diff transaction complete - generating change data")
      (diff/make-transaction-data! diff-opts))

    (catch Exception e
        {:errors [{::anomalies/category ::anomalies/fault
                   ::anomalies/message (.getMessage e)
                   ::anomalies/ex-data (ex-data e)}]})))


(defn crosscheck-references
  "Checks all reference data in the tx-data dir of target-dir to see if it asserts anything
  about unique reference ids that differs from what's already in the database. Returns list
  of differences (if any)."
  [{:keys [target-dir datomic-uri schema-directory]}]
  (let [_tx-data-dir (str target-dir "/" "tx-data")
        ensured-cfg (db/ensure-db schema-directory datomic-uri)
        ref-files (conventions/ref-tx-data-filenames target-dir)]
    (mapcat (partial upsert-coord/report-upserts ensured-cfg) ref-files)))


(defn run
  "Runs a complete, end-to-end import of unify data."
  [ctx]
  (let [_emit-import-result (prepare-import ctx)
        tx-result (transact-import ctx)]
    tx-result))

(comment
  (keep :kind/name (db.schema/get-metamodel-and-schema)))