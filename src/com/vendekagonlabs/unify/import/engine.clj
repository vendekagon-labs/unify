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

(ns com.vendekagonlabs.unify.import.engine
  (:require [clojure.data.csv :as csv]
            [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [clojure.set :as set]
            [clojure.core.async :as a]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cognitect.anomalies :as anom]
            [com.vendekagonlabs.unify.db.metamodel :as metamodel]
            [com.vendekagonlabs.unify.matrix :as matrix]
            [com.vendekagonlabs.unify.import.engine.parse.config :as parse.config]
            [com.vendekagonlabs.unify.import.engine.parse.mapping :as parse.mapping]
            [com.vendekagonlabs.unify.import.engine.parse.matrix :as parse.matrix]
            [com.vendekagonlabs.unify.import.engine.parse.data :as parse.data]
            [com.vendekagonlabs.unify.util.io :as io]
            [com.vendekagonlabs.unify.util.text :as text]
            [com.vendekagonlabs.unify.util.uuid :as uuid]
            [com.vendekagonlabs.unify.validation.record :as record]
            [com.vendekagonlabs.unify.db.schema :as schema]
            [com.vendekagonlabs.unify.util.collection :as coll]))

(set! *warn-on-reflection* true)

(defn available-processors []
  (.availableProcessors (Runtime/getRuntime)))

(System/setProperty "clojure.core.async.pool-size"
                    (str (+ 2 (available-processors))))

(defn threads-per-file []
  (or (Long/getLong "com.vendekagonlabs.unify.prepare.threads")
      (+ 2 (available-processors))))

(defmacro csv-throw->anomaly
  [body]
  `(try
     ~body
     (catch Exception e#
       {::anom/category              ::anom/incorrect
        :csv-file/could-not-read-row {:cause (or (ex-data e#)
                                                 (.getMessage e#))}})))

(defn record-stream->chan
  "Returns a map with file header as :header and channel from which records contained
  in file cane be taken."
  [rdr]
  (let [ch (a/chan 10000 (map (fn trim-whitespace [[row line-no]]
                                [(mapv str/trim row) line-no])))]
    (a/go-loop [tsv-lines (csv/read-csv rdr :separator \tab)
                line-no 1]
      (if-let [this-line (csv-throw->anomaly (first tsv-lines))]
        (let [rest-lines (csv-throw->anomaly (next tsv-lines))]
          (if (a/>! ch [this-line line-no])
            (recur rest-lines (inc line-no))
            (a/close! ch)))
        (a/close! ch)))
    {:header  (first (a/<!! ch))
     :channel ch}))

(defn get-req-column-names
  [job]
  (let [unify-attr (remove nil?
                           [(:unify/variable job)
                            (get-in job [:unify/reverse
                                         :unify/rev-variable])])]
    (->> job
         (mapcat
           (fn [[k v]]
             (if (= "unify" (namespace k))
               []
               (cond
                 (and (map? v)
                      (:unify/many-delimiter v))
                 [(:unify/many-variable v)]

                 (vector? v)
                 v

                 :else
                 [v]))))
         (concat unify-attr))))

(defn ensure-job+header!
  "Throws in cases where columns are specified in the config file directive, but are
  not present in the header."
  [job header channel]
  (let [req-cols (into #{} (get-req-column-names job))
        hdr-set (into #{} header)
        missing-cols (seq (set/difference req-cols hdr-set))
        in-file (or (:unify/input-tsv-file job)
                    (:unify/input-csv-file job))]
    (when missing-cols
      (a/close! channel)
      (Thread/sleep 500)
      (throw (ex-info (str "Directive specified use of columns not found in file: " in-file)
                      {:data-file/header-mismatch {:filename         (text/file->str in-file)
                                                   :cols-not-in-file missing-cols
                                                   :other-causes     {:wrong-format [:not-tab-separated]}}})))))

(defn comp->anomalies
  "Return composition of fns with one modification: if fn in chain encounters an anomaly, that anomaly
  propagates through end of composition without calling other fns in chain."
  [& fns]
  (reduce comp (for [f fns]
                 (fn anomaly-probe [& args]
                   (let [probe (first args)]
                     (if (::anom/category probe)
                       probe
                       (apply f args)))))))

(defn process-file-async
  "Runs process-fn on all records in file with concurrency of conc."
  [full-import-ctx job in-f out-f conc]
  (with-open [rdr (jio/reader in-f)
              writer (clojure.java.io/writer out-f)]
    (let [out-ch (a/chan 10000)
          done-ch (a/chan)
          {:keys [header channel]} (record-stream->chan rdr)
          exit+cleanup (fn exit+cleanup [err-val]
                         (a/close! out-ch)
                         (a/close! channel)
                         (a/go
                           (a/>! done-ch err-val)))
          na-val-set (let [raw-na (:unify/na job)]
                       (if (coll? raw-na)
                         (set raw-na)
                         #{raw-na}))
          const-data (parse.data/extract-constant-data
                       full-import-ctx
                       job
                       na-val-set)
          job+ (update-in job [:unify/precomputed] merge const-data)
          xf (comp->anomalies
               (map (fn parse-data-transducer-fn [[record line-n]]
                      (parse.data/record->entity
                        full-import-ctx
                        job+
                        header
                        record
                        na-val-set
                        {:line-number line-n
                         :line        record
                         :filename    (text/file->str in-f)})))
               (remove #(= % :unify/omit))
               (map record/validate))]
      (ensure-job+header! job header channel)
      (a/go-loop [total 0]
        (when (zero? (mod total 1000))
          (print ".") (flush))
        (if-let [result (a/<! out-ch)]
          (if (::anom/category result)
            (a/<! (exit+cleanup result))
            (do (try
                  (binding [*out* writer]
                    (prn result))
                  (catch Exception e
                    (a/<!
                      (exit+cleanup (merge (ex-data e)
                                           {::anom/category            ::anom/fault
                                            :engine/output-file-closed {:out-file out-f
                                                                        :message  (.getMessage e)}})))))
                (recur (inc total))))
          (a/>! done-ch {:line-count total})))

      (a/pipeline-blocking
        conc
        out-ch
        xf
        channel)
      (a/<!! done-ch))))


(defn process-file
  "Runs process-record-f function on all records in file."
  [full-import-ctx job in-f out-f]
  (log/info (str "Generating entity data from '" in-f "' to output file '" out-f "':"))
  (let [process-ctx {:job job :in-filename in-f :out-filename out-f}
        conc (threads-per-file)
        result (process-file-async full-import-ctx job in-f out-f conc)]
    (if (::anom/category result)
      (do (log/error :engine/process-file "\n" "Error processing record")
          (throw (ex-info (str "Error processing file: " in-f)
                          (merge {:engine/input-file (text/file->str in-f)}
                                 (dissoc result ::anom/category)))))
      (merge process-ctx result))))

(defn job->file
  "Takes a job & args-map (including :root-ctx-cfg :schema :mapping)
   and calls the process-file fn to do the work"
  [full-import-ctx job target-dir]
  (let [outfile-prefix (:unify/out-file-prefix job)
        in-file (clojure.java.io/file (or (:unify/input-tsv-file job)
                                          (:unify/input-csv-file job)))
        in-f-name (.getName in-file)
        out-f-path (str target-dir
                        (when-not (clojure.string/ends-with? target-dir "/") "/")
                        outfile-prefix
                        in-f-name
                        "-"
                        (uuid/v5 :unify/job job) ".edn")]
    (if (and (:resume full-import-ctx)
             (io/exists? out-f-path))
      (do (log/info "Prepare is in [resume] mode, skipping existing entity file: " out-f-path)
          {:job                 job
           :in-filename         in-f-name
           :out-filename        out-f-path
           :prepare.resume/skip true})
      (process-file full-import-ctx job in-file out-f-path))))


(defn run-jobs!
  [target-dir full-import-ctx jobs continue-on-error?]
  (let [exec-job (fn exec-job [job]
                   (try
                     (let [import-start (System/currentTimeMillis)
                           result (job->file full-import-ctx job target-dir)]
                       (assoc result :import/elapsed-time (- (System/currentTimeMillis) import-start)))
                     (catch Exception e
                       (merge (ex-data e)
                              {::anom/category ::anom/fault
                               :async/file     (or (:unify/input-tsv-file job)
                                                   (:unify/input-csv-file job))}))))
        results (doall (map exec-job jobs))]
    (if-let [errors (seq (filter ::anom/category results))]
      (let [errored-file (:async/file (first errors))]
        (if continue-on-error?
          (do (log/error "Encountered error preparing file:" errored-file)
              (doseq [error errors]
                (log/error error))
              errors)
          (throw (ex-info (str "Encountered error preparing file: " errored-file)
                          (dissoc (first errors) ::anom/category :async/file))))))
    results))

(defn ensure-import-files-exist
  "Throws if all import files do not exist."
  [root-dir jobs]
  ;; two keeps on the same collection isn't the most efficient way to do this, but these
  ;; are going to be small (typed in by humans), though due to ugliness should probably fix
  ;; before shipping... using "input-file" name independent of namespace maybe?
  (let [files (concat (keep :unify/input-tsv-file jobs)
                      (keep :unify/input-csv-file jobs)
                      (keep :unify.matrix/input-file jobs))]
    (when-let [not-found (seq (keep (fn [file]
                                      (let [fname (str (when (text/filename-relative? file)
                                                         root-dir)
                                                       file)]
                                        (when-not (io/exists? fname)
                                          fname)))
                                    files))]
      (throw (ex-info (str "The following import file(s) are not found: "
                           (str/join ", " not-found))
                      {:import-config/missing-import-files not-found})))))

(defn write-matrix-data-file!
  [target-dir {:keys [unify.matrix/input-file
                      unify.matrix/output-file
                      unify.matrix/constant-columns
                      unify.matrix/header-substitutions]}]
  (matrix/prepare-matrix-file! target-dir input-file output-file header-substitutions constant-columns))

(defn write-matrix-entity-file!
  [target-dir entity-data]
  (let [out-f-name (uuid/v5 "unify.matrix" entity-data)
        ;; Matrix files never assert new entities that are valid reference targets,
        ;; and in fact since these values never are asserted in the database, this
        ;; is impossible. These should always get transacted last.
        out-f-prefix "99-priority"
        out-f-path (str target-dir
                        (when-not (clojure.string/ends-with? target-dir "/") "/")
                        out-f-prefix "-"
                        out-f-name ".edn")
        cleaned-entity-data (coll/remove-keys-by-ns entity-data "unify")]
    (io/write-edn-file out-f-path cleaned-entity-data)
    entity-data))

(defn run-matrix-jobs!
  "Run each matrix job as specified in the directive, return entity map or anomaly map
  for each completed job."
  [target-dir full-import-ctx matrix-jobs continue-on-error?]
  ;; refactor to async/pipeline once working, doall map until then
  (let [job-results (for [matrix-job matrix-jobs]
                      (parse.matrix/matrix->entity
                        full-import-ctx matrix-job))]
    ;; when wiring async these should propagate anomaly states or otherwise execute
    (->> job-results
         (map (partial write-matrix-entity-file! target-dir))
         (map (partial write-matrix-data-file! target-dir))
         (doall))

    ;; TODO: fix up job results to match result reporting from other file processing fns
    job-results))


(defn create-entity-data
  "Given a schema and a config map, generate entity map files and write to target-dir."
  [schema cfg-map cfg-root-dir target-dir resume? continue-on-error?]
  (try
    (let [start (System/currentTimeMillis)
          raw-mapping-file-path (get-in cfg-map [:unify/import :mappings])
          _ (when-not raw-mapping-file-path
              (throw (ex-info "Mapping file must be supplied in [:unify/import :mappings] of import config."
                              {:config/invalid-mapping {:unify/import-config-mappings ::no-mappings-entry}})))
          mapping-file-path (str (when (text/filename-relative? raw-mapping-file-path) cfg-root-dir)
                                 raw-mapping-file-path)

          mapping-lookup (parse.mapping/mappings-edn->lookup (edn/read-string (slurp mapping-file-path)))
          parsed-cfg (parse.config/parse-config-map schema cfg-map)
          dataset-entity (parse.config/cfg-map->dataset-entity schema mapping-lookup parsed-cfg)
          jobs (parse.config/cfg-map->directives schema mapping-lookup cfg-root-dir parsed-cfg)
          import-entity (parse.config/cfg-map->import-entity cfg-map)
          matrix-jobs (parse.config/cfg-map->matrix-directives schema mapping-lookup cfg-root-dir parsed-cfg)

          full-import-ctx {:parsed-cfg  parsed-cfg
                           :schema      schema
                           :mapping     mapping-lookup
                           :resume      resume?
                           :import-name (:import/name import-entity)}

          _ (io/write-edn-file (str target-dir "/import-job.edn") import-entity)
          import-file (str target-dir "/import-job.edn")
          _ (pp/pprint dataset-entity
                       (clojure.java.io/writer import-file :append true))

          _ (ensure-import-files-exist cfg-root-dir (concat matrix-jobs jobs))
          ref-only-cfg-map (select-keys cfg-map (metamodel/allowed-ref-data schema))
          ref-data-jobs (parse.config/reference-data-jobs
                          schema
                          ref-only-cfg-map
                          cfg-root-dir)
          _ (println "\nUsing" (threads-per-file) " threads.")
          ref-data-results (run-jobs! target-dir full-import-ctx ref-data-jobs continue-on-error?)
          matrix-results (run-matrix-jobs! target-dir full-import-ctx matrix-jobs continue-on-error?)
          dataset-results (run-jobs! target-dir full-import-ctx jobs continue-on-error?)
          results (vec (concat ref-data-results matrix-results dataset-results))]
      (io/write-edn-file (str target-dir "/import-summary.edn") (text/->pretty-string results))
      (println "\n")
      (log/info (str "Entity generation elapsed time: " (/ (- (System/currentTimeMillis) start) 1000.0) "seconds."))
      results)
    (catch Exception e
      (let [data (ex-data e)
            unifyty-data (text/->pretty-string data)
            err-fname "PRET_ERROR_DUMP"]
        (log/error "Engine encountered exception while generating entity data, error state in file: "
                   err-fname)
        (io/write-edn-file err-fname
                           (str "Error Message:\n" (.getMessage e)
                                "\n\nException Information:\n" unifyty-data
                                "\nException Stack Trace:\n  - " (text/stacktrace->string e)))
        (throw e)))))

(comment
  (require '[com.vendekagonlabs.unify.db.schema :as schema])
  (require '[clojure.pprint :as pp])
  (def unify-schema (schema/get-metamodel-and-schema))
  (def cfg-map (io/read-edn-file "test/resources/matrix/config.edn"))
  (def cfg-root-dir "/Users/bkamphaus/code/unify/test/resources/matrix/")
  (def target-dir "/Users/bkamphaus/scratch/here-we-go")
  (def ent-results
    (create-entity-data
      unify-schema
      cfg-map
      cfg-root-dir
      target-dir
      false
      false))
  (count ent-results)
  (pp/pprint
    (nth ent-results 1)))
