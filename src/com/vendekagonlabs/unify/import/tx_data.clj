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
(ns com.vendekagonlabs.unify.import.tx-data
  (:require [clojure.spec.alpha :as s]
            [clojure.edn :as edn]
            [clojure.walk :as w]
            [datomic.api :as d]
            [com.vendekagonlabs.unify.db.metamodel :as metamodel]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [com.vendekagonlabs.unify.util.text :as text]
            [com.vendekagonlabs.unify.matrix :as matrix]
            [com.vendekagonlabs.unify.util.io :as pio]
            [com.vendekagonlabs.unify.db.import-coordination :as ic]
            [com.vendekagonlabs.unify.db.transact :refer [run-txns!
                                                          sync+retry]]
            [clojure.edn :as edn]
            [cognitect.anomalies :as anom]
            [clojure.core.async :as a]
            [com.vendekagonlabs.unify.import.file-conventions :as conventions]
            [com.vendekagonlabs.unify.util.uuid :as uuid]
            [com.vendekagonlabs.unify.db.schema :as db.schema])
  (:import (java.io PushbackReader)
           (java.util UUID)))


;; Import Metadata Specs --------------------------------------------------
;;
(def uuid-regex #"^[{]?[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}[}]?$")
(defn str-uuid? [v]
  (and (string? v)
       (re-find uuid-regex v)))

(s/def ::valid-tx-form
  (s/or :map-form (s/and map? #(every? namespace (keys %)))
        :list-form seq))

(s/def ::valid-tx-data
  (s/and (s/coll-of ::valid-tx-form)))

(s/def :unify.import.tx/id str-uuid?)

(s/def :unify.import.tx/import
  (s/and #(vector? %)
         #(= (first %) :unify.import/name)
         #(string? (second %))))

(s/def ::metadata
  (s/and (s/keys :req [:unify.import.tx/id :unify.import.tx/import])
         ::valid-tx-form))


;; Prepare Fns ------------------------------------------------------------
;;

(defn- hash-uid
  "Hashes a uid such as [dataset-name /composite/uid/with/path] so that the second part
  becomes the md5 of the string. This is necessary because Datomic limits string length
  in tuple fields to 256 characters"
  [uid-tuple]
  (let [[dataset-name composite-id] uid-tuple]
    [dataset-name (uuid/string->md5-hash composite-id)]))

(defn hash-uids
  "Hashes all uids in tx-data. All-uids is a set of all the uid attributes that
  need to be hashed, as returned by metamodel/all-uids"
  [tx-data all-uids]
  (w/prewalk
    (fn [x]
      (if (and (vector? x)
               (contains? all-uids (first x)))
        [(first x) (hash-uid (second x))]
        x))
    tx-data))


(defn create-txn-metadata
  "Return the :unify.import.tx/id and :unify.import.tx/import transaction metadata for the given
  UUID and import-name"
  [import-name]
  {:db/id         "datomic.tx"
   :unify.import.tx/id (str (UUID/randomUUID))
   :unify.import.tx/import [:unify.import/name import-name]})


(defn process-one-file!
  "Lazily process filename-in, one element at a time.
  Emit the data as batches of size 'batch' entities to filename-out.
  Each batch will also include a :unify.import.tx/id UUID and :unify.import.tx/import reference to the import entity

  'ctx' is a map containing at least the :unify.import/name for the current import job
  'batch' is the number of entities per transaction"
  [import-job-name filename-in filename-out batch]
  (with-open [in (PushbackReader. (io/reader filename-in))
              out (io/writer filename-out)]
    (let [all-uids (metamodel/all-uids (db.schema/get-metamodel-and-schema))
          input-seq (->> (repeatedly #(edn/read {:eof ::eof} in))
                         (take-while #(not= % ::eof)))]
      (doseq [data (->> input-seq
                        (partition-all batch)
                        (map (fn [tx-batch]
                               (conj (hash-uids tx-batch all-uids)
                                     (create-txn-metadata
                                       import-job-name)))))]
        (binding [*out* out]
          (prn data)))))
  ;; print one dot per file.
  (do (print ".") (flush))
  (log/info "Generated tx-data for: " filename-out)
  [:completed filename-out])




(defn make-transaction-data*!
  "Transform a set of entity data files into transaction data files.
  Ignores the import-job edn file

  'ent-file-path' is path to a folder containing entity-map files
  'tx-data-path' is output path for transaction data files
  'ctx' is a map containing at least :unify.import/name for the current import job
  'batch' is the size (# of entities) batches to create"
  [import-job-name ent-file-path batch]
  (log/info "Generating transaction data from entity data.")
  (let [workers 40
        all-fnames (conventions/all-entity-filenames ent-file-path)
        tx-data-gen (fn [abs-filepath]
                      (process-one-file!
                        import-job-name
                        abs-filepath
                        (conventions/in-tx-data-dir ent-file-path (text/filename abs-filepath))
                        batch))
        input-ch (a/to-chan!! all-fnames)
        result-ch (a/chan workers)]
    (a/pipeline-blocking workers result-ch (map tx-data-gen) input-ch)
    (a/<!! (a/into [] result-ch))))


(defn process-import-cfg-file!
  "Read the import-cfg file, add txn metadata to the data literal and output the
  import entity first as a single transaction, followed by a transaction for the annotated
  literal data map
  Return the :unify.import/name of the job"
  [working-dir]
  (let [in-file-path (conventions/import-cfg-job-path working-dir)
        out-file-path (conventions/tx-import-cfg-job-path working-dir)
        cfg-file-data (edn/read-string (str "[" (slurp in-file-path) "]"))
        import-ent (first cfg-file-data)
        import-job-name (:unify.import/name import-ent)
        processed-import-ent {:db/id         "datomic.tx"
                              :unify.import.tx/import (assoc import-ent :db/id "temp-import-ent")
                              :unify.import.tx/id (str (UUID/randomUUID))}
        all-uids (metamodel/all-uids (db.schema/get-metamodel-and-schema))
        literal-data (conj (hash-uids (list (second cfg-file-data)) all-uids)
                           (create-txn-metadata import-job-name))]
    (do
      (pio/write-edn-file out-file-path (list processed-import-ent))
      (spit out-file-path literal-data :append true))
    import-job-name))


(defn make-transaction-data!
  "Processes import-cfg-job edn file into transaction data, with the
  import entity as a single first transaction and the literal data map following it.
  Also gets the import name from the import-entity to pass to
  make-transaction-data*! to process the remaining files in the directory"
  [target-dir batch]
  (println "Generating transaction data.")
  (let [start (System/currentTimeMillis)
        import-job-name (process-import-cfg-file! target-dir)
        process-result (make-transaction-data*! import-job-name target-dir batch)]
    ;; end .... tx-data progress reporting with newline
    (println)
    (log/info (str "Transaction data generated from entity data in: "
                   (/ (- (System/currentTimeMillis) start)
                      1000.0) " seconds."))
    {:import-job-name import-job-name :result process-result}))


;; Transacting Fns -------------------------------------------------------------------
;;

;; Sync transaction
;;
;; Used to transact the import job file (import-job.edn) which
;; Contains import metadata about the files that will be
;; transacted.
;;

(defn- transact-one-file-sync!
  "Synchronously transact a single file of transaction data."
  [conn f-path {:keys [import-name] :as _opts}]
  (with-open [in (PushbackReader. (io/reader f-path))]
    (let [tx-seq (->> (repeatedly #(edn/read {:eof ::eof} in))
                      (take-while #(not= % ::eof)))
          uuid-set (ic/successful-uuid-set (d/db conn) import-name {:invalidate false})]
      (reduce (fn [results tx]
                (if-not (uuid-set (:unify.import.tx/id (first tx)))
                  (conj results (sync+retry conn tx 3000 10))
                  results))
              []
              tx-seq))))

(defn run-import-job-file!
  "Transact the import-cfg literal file."
  [conn import-job-file-path {:keys [import-name dataset-name] :as opts}]
  (log/info "run-import-job-file!> Transacting literal data from import config file: " import-job-file-path)
  (let [tx-result (transact-one-file-sync! conn import-job-file-path opts)]
    (log/info "run-import-job-file!> Completed transacting import-cfg-file: " import-job-file-path)
    tx-result))

;; Async transactions


(defn run-ordered-file-imports!
  "Import data files into Datomic.
  Individual files are transacted via pipeline with concurrency conc, but
  the function waits until each file is complete before starting the next one.
  Files are transacted in order according to the lexical ordering (sort) of their
  filename (not full path)."
  [conn file-paths conc {:keys [import-name dataset-name skip-annotations] :as opts}]
  (if (> (count file-paths) 0)
    (let [sorted-file-paths (sort-by text/filename file-paths)]
      (loop [all-results []
             file-list sorted-file-paths]
        (log/info "run-ordered-file-imports!> Running " (first file-list))
        (let [run-result (a/<!! (:result (run-txns! conn [(first file-list)] conc opts)))
              cur-results (conj all-results run-result)]
          (if (and (seq (rest file-list))
                   (not (::anom/category run-result)))
            (recur cur-results (rest file-list))
            cur-results))))
    (log/warn "No files passed to file import ")))


(defn transact-import-data!
  "Transact a full import's data into the Datomic database via the conn.
  Reference data should be transacted first, followed by the data in
  'import-config-entity-data.edn'. The remaining data files can be
  transacted in any order.

  'target-dir' is the path to the working directory
  'conn' is the connection to a Datomic database against which to transact
  'conc' is the concurrency for the pipeline

  Update mode:

  When update is true, then this transaction will use the transaction
  data in the tx-data/update directory instead of the prepared data
  in tx-data. This operation still transacts reference data in the tx-data
  before the update transactions."
  [target-dir datomic-uri conc {:keys [resume
                                       skip-annotations
                                       disable-remote-calls] :as _opts}]
  (let [transact-dir target-dir
        import-job-name (conventions/import-name target-dir)
        conn (d/connect datomic-uri)
        db (d/db conn)
        all-dataset-fnames (conventions/dataset-tx-data-filenames transact-dir)
        all-ref-fnames (conventions/ref-tx-data-filenames target-dir)
        import-job-file-path (conventions/tx-import-cfg-job-path transact-dir)]
    (log/info "Running transactions for import: " import-job-name
              ::target-dir target-dir ", " ::update update)
    (when (and resume
               (not (ic/import-entity-txn-eid db import-job-name)))
      (log/error "Could not find import-name: " import-job-name)
      (throw (ex-info (str "Import name:" import-job-name " does not exist.")
                      {:import-name/does-not-exist import-job-name})))

    (let [read-opts {:import-name      import-job-name
                     :skip-annotations skip-annotations}

          matrix-upload (if-not disable-remote-calls
                          (matrix/upload-matrix-files! target-dir)
                          [true])

          import-literal-results (if-not resume
                                   (run-import-job-file! conn import-job-file-path read-opts)
                                   [{::skip true}])

          ref-results (cond
                        ;; if literal import did not succeed, log error (and implicit nil)
                        (or (not import-literal-results)
                            (seq (filter ::anom/category import-literal-results)))
                        (log/error "Skipping reference data because import data literal tx failed.")

                        ;; if no reference files, don't need to transact
                        (empty? all-ref-fnames)
                        (do
                          (println "Skipping ref data")
                          (log/info "No reference data to transact (skipping reference data step).")
                          [{:completed 0}])

                        ;; if no ref anomalies, proceed to transact import literal
                        :else
                        (do
                          (log/info "Transacting reference data.")
                          (run-ordered-file-imports! conn all-ref-fnames conc read-opts)))

          data-results (if (and ref-results
                                (not (seq (filter ::anom/category ref-results))))
                         (do
                           (log/info "Transacting normal data.")
                           (run-ordered-file-imports! conn all-dataset-fnames conc read-opts))
                         (log/error "Skipping normal data because all reference data did not transact."))]
      {:import-literal-result import-literal-results
       :ref-results           ref-results
       :matrix-results        [matrix-upload]
       :data-results          data-results})))
