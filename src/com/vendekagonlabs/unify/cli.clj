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
(ns com.vendekagonlabs.unify.cli
  (:require [clojure.tools.logging :as log]
            [com.vendekagonlabs.unify.db.backend :as backend]
            [clojure.tools.cli :as tools.cli]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [com.vendekagonlabs.unify.import :as import]
            [com.vendekagonlabs.unify.db.schema :as schema]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [com.vendekagonlabs.unify.util.text :as text]
            [com.vendekagonlabs.unify.cli.error-handling :as cli.error-handling :refer [exit]]
            [com.vendekagonlabs.unify.db :as db]
            [com.vendekagonlabs.unify.db.schema.cache :as cache]
            [com.vendekagonlabs.unify.import.file-conventions :as conventions]
            [com.vendekagonlabs.unify.util.release :as release])
  (:gen-class)
  (:import (java.util Date)))


(def unify-ascii "
==================================================
   __    __  .__   __.  __   ___________    ____
  |  |  |  | |  \\ |  | |  | |   ____\\   \\  /   /
  |  |  |  | |   \\|  | |  | |  |__   \\   \\/   /
  |  |  |  | |  . `  | |  | |   __|   \\_    _/
  |  `--'  | |  |\\   | |  | |  |        |  |
   \\______/  |__| \\__| |__| |__|        |__|
==================================================")

;; this handles case when pmap, etc. cause exceptions outside main
;; thread that don't hit normal exception handling.
;; see: https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions
(Thread/setDefaultUncaughtExceptionHandler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread ex]
      (log/error ex "Uncaught exception on" (.getName thread))
      (cli.error-handling/report-and-exit ex))))


(defn usage [options-summary]
  (->> [""
        "Usage: unify task [parameters]"
        ""
        "Options:"
        options-summary
        ""
        "Task:"
        "  request-db        Creates a new Unify database. Specify the name of the database with --database"
        "                    and the schema to install with --schema-directory"
        "  list-dbs          Lists information about all current databases."
        "  delete-db         Deletes the database specified by --database"
        "  prepare           Uses an import config file to generate all data needed to run an import."
        "                    Requires --import-config, --working-directory and --schema-directory args."
        ;;        "  diff              Generates all changes required to update an existing dataset to match the target."
        ;;"                    Requires --working-directory and --database arguments."
        "  transact          Transacts all data (as created by prepare) for an import job --working-directory"
        "                    into database specified by --database."
        "                    Optionally use --update to transact updated changes instead of prepared transactions."
        "                    If an imports fails due to eg network latency or process termination, you can resume"
        "                    with --resume"
        ;;"  validate          Runs validation checks against the database."
        ;;"                    Requires --working-directory and --database arguments when working with branch databases."
        ;;"  crosscheck-reference   Checks all reference data files for potential upsert collisions."
        ;;"                         Requires --database and --working-directory args."
        ""]
       (str/join \newline)))

(def cli-options
  [[nil "--import-config     IMPORT-CONFIG" "Import config edn file"]
   [nil "--working-directory WORKING-DIRECTORY"
    "Directory where prepared data goes, transact uses the data prepare puts here."]
   [nil "--schema-directory  SCHEMA-DIRECTORY"
    "Directory containing a Unify schema (base Datomic schema + metamodel annotations)"]
   [nil "--tx-batch-size     TX-BATCH-SIZE" "Datomic transaction batch size"
    :default 50
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 20 % 200) "Transaction batch size must be between 20 and 200 in length."]]
   [nil "--database          DATABASE-NAME" "The name of the database to perform command against."]
   ;; --overwrite option disabled for now as it exhibits inconsistent behavior on different OSes, directory structures.
   ; [nil "--overwrite" "When set on prepare, will delete and rewrite working directory if it already exists."]
   [nil "--resume" "When set, will resume a previously started transact command."
    :default false]
   [nil "--update" "When set, will transact the changes generated from diff instead of prepare"
    :default false]
   [nil "--skip-annotations" "When set, will not transact annotations into database."
    :default false]
   [nil "--continue-on-error" "When set, unify prepare will continue when encountering errors and report all errors at end."
    :default false]
   ["-h" "--help"]])


(defn print-db-version [datomic-uri]
  (println "Target db schema version" (db/version datomic-uri)))

(defn request-db
  [{:keys [database
           schema-directory] :as args}]
  (if-not database
    (exit 1 (str "ERROR: request-db requires a --database argument"))
    (try
      (let [result (backend/request-db database)]
        (if (:error result)
          (exit 1 (str "ERROR: request-db failed with: " result))
          (let [db-info (db/fetch-info database)
                uri (:uri db-info)]
            (db/init uri :schema-directory schema-directory)
            (println "Request successful, created database" (:db-name result))))
        :success)
      (catch Exception e
        (exit 1 (str "Error encountered creating Unify database " database "\n"
                     (when-let [err-data (ex-data e)]
                       (text/->pretty-string (dissoc err-data :message)))
                     \newline
                     (.getMessage e)))))))

(defn list-dbs [_]
  (let [result (backend/list-dbs)]
    (when (:error result)
      (exit 1 (str "Error: " result)))
    (doseq [db result]
      (println db))))

(defn delete-db
  [{:keys [database]}]
  (if database
    (let [result (backend/delete-db database)]
      (if (:success result)
        (println "Database " database " has been deleted.")
        (do
          (println "Error deleting " database "errors: ")
          (pprint result))))
    (exit 1 (str "ERROR: requires --database argument"))))

(defn prepare
  [{:keys [import-cfg-file target-dir resume overwrite
           schema-directory continue-on-error] :as arg-map}]
  (when-not schema-directory
    (exit 1 (str "ERROR: prepare requires --schema-directory argument.")))
  (when continue-on-error
    (println "Unify will attempt to continue when prepare encounters errors. Will report all errors at end and in logs."))
  (when-not (and import-cfg-file
                 (util.io/exists? import-cfg-file))
    (exit 1 (str "ERROR: Import config not specified or does not exist: " import-cfg-file)))
  (when-not target-dir
    (exit 1 "ERROR: Must pass working-directory argument to prepare."))
  (when (and (util.io/exists? target-dir)
             (not (util.io/empty-dir? target-dir))
             (not resume)
             (not overwrite))
    (exit 1 (str "Specified working directory: " target-dir " exists and is non-empty.")))
  ;; if working dir exists and overwrite is set, delete it.
  (when (and (util.io/exists? target-dir)
             (println "Working directory exists, *overwrite* is deleting:" target-dir)
             overwrite)
    (util.io/delete-recursively target-dir))
  (import/prepare-import arg-map))


(defn transact
  [{:keys [target-dir resume database datomic-uri skip-annotations update] :as ctx}]
  (when-not (and datomic-uri database)
    (exit 1 "ERROR: Transact needs a database to transact to."))
  (print-db-version datomic-uri)
  (when resume
    (println (str "WARN: Resuming transaction job. This will skip transacting the import job entity. "
                  "Transactions may take awhile to restart as previously successful IDs are found.")))
  (when-not (and target-dir (util.io/exists? target-dir))
    (exit 1 (str "ERROR: Working Directory arg must be passed, directory must exist, and "
                 "it must already contain transaction and entity data as created by prepare.")))
  (let [returned (import/transact-import ctx)]
    (if-let [errors (:errors returned)]
      (do (println "Transactions did not all complete successfully, see logs. Anomalies: " errors)
          returned)
      (println "Completed " (get-in returned [:results :completed])
               " transactions, entire import job at " target-dir))))


#_(defn diff
    [{:keys [target-dir resume datomic-uri skip-annotations database] :as ctx}]
    (when-not (and datomic-uri database)
      (exit 1 "ERROR: Diff needs a database to transact to."))
    (print-db-version datomic-uri)
    (when resume
      (println (str "WARN: Resuming transaction job. This will skip transacting the import job entity. "
                    "Transactions may take awhile to restart as previously successful IDs are found.")))
    (when-not (and target-dir (util.io/exists? target-dir))
      (exit 1 (str "ERROR: Working Directory arg must be passed, directory must exist, and "
                   "it must already contain transaction and entity data as created by prepare.")))

    (let [returned (import/perform-diff ctx)]
      (if-let [errors (:errors returned)]
        (do (println "Diff did not complete successfully, see logs. Anomalies: " errors)
            returned)
        (println "\nCompleted. Diff data in: "
                 (conventions/diff-tx-dir target-dir)))))

#_(defn validate
    [{:keys [working-directory database] :as ctx}]
    (import/validate {:database          database
                      :working-directory working-directory}))


#_(defn format-crosscheck-conflict
    "Formatter to make crosscheck conflict results easier to read."
    [{:keys [transaction-entity database-entity difference]}]
    (let [{:keys [filename line-number]} (:unify/annotations transaction-entity)]
      (str "File: " filename " at line-number: " line-number "\n"
           (apply str
                  (text/->pretty-string (dissoc transaction-entity :unify/annotations :unify/identifying-avpair))
                  (for [attr (keys difference)]
                    (str "\nTransaction file specified " attr " with value "
                         (get transaction-entity attr)
                         "\nDatabase contains " attr " with value "
                         (get database-entity attr)))))))

#_(defn crosscheck-reference
    [{:keys [target-dir] :as ctx}]
    (let [tx-data-dir (str target-dir "/" "tx-data")]
      (when-not (util.io/exists? tx-data-dir)
        (log/error "What happened to error handling here?")
        (exit 1 "Directory with transaction data must already exist when crosscheck references."))
      (let [crosscheck-result (import/crosscheck-references ctx)]
        (if (empty? crosscheck-result)
          (println "Reference data crosscheck complete. No reference data collisions detected.")
          (do
            (println "Crosscheck Complete. Collisions detected:")
            (println "-----------------------------------------")
            (doseq [conflict crosscheck-result]
              (println (format-crosscheck-conflict conflict))
              (println "---------------------")))))))


(def tasks
  {"request-db" request-db
   "prepare"    prepare
   ;;   "diff" diff
   "transact"   transact
   ;;   "validate" validate
   ;;   "crosscheck-reference" crosscheck-reference
   "list-dbs"   list-dbs
   "delete-db"  delete-db})

(def allowed-tasks (set (keys tasks)))


(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary] :as argmap} (tools.cli/parse-opts args cli-options)]
    (cond
      ;; If help, we print usage
      (:help options)
      {:exit-message (usage summary) :ok? true}

      errors
      {:exit-message (exit 1 errors)}

      (= 1 (count arguments))
      (let [task (first arguments)]
        (if-not (allowed-tasks task)
          {:exit-message
           (str "Unknown task:" task)
           :ok? false}

          ;; pass back the argmap to be parsed into a task
          ;;
          (assoc argmap :ok? true)))
      :else
      {:exit-message (usage summary)})))

;; TODO: this is a bad way to do this, but is it a priority to fix? ¯\_(ツ)_/¯
;; this really shouldn't be called at arg parse level, but instead for specific
;; tasks that need the URI (i.e. change their params to database-name, then get
;; uri.
(defn database-name->datomic-uri
  "Loads the datomic-uri for the given database for tasks that
   require it (like transact, etc). Skips loading for tasks
   that provide a database name but don't use it (like request-db
   which creates the database, etc)."
  [task database-name]
  (when-not (= task "request-db")
    (let [datomic-info (db/fetch-info database-name)
          datomic-uri (:uri datomic-info)]
      (when-not datomic-uri
        (throw (ex-info (str "No such database: " database-name
                             "\nEither name is wrong, or database has been deleted due to inactivity.")
                        {:cli/no-such-database database-name})))
      datomic-uri)))

(defn parse-task-args [argmap]
  (let [{:keys [options arguments errors summary]} argmap
        {:keys [import-config
                database
                working-directory
                skip-annotations
                overwrite
                resume
                update]} options
        task (first arguments)]
    (cond-> {:task       task
             :target-dir working-directory}
            database (assoc :datomic-uri (database-name->datomic-uri task database))
            import-config (assoc :import-cfg-file import-config)
            working-directory (assoc :target-dir working-directory)
            options (assoc :options options))))


(defn- elapsed [start end]
  (let [diff (- (.getTime end) (.getTime start))]
    (float (/ diff 1000))))


(defn -main [& args]
  (println unify-ascii)
  (try
    (println "version:" (release/version))
    (let [arg-map (validate-args args)
          {:keys [exit-message ok?]} arg-map]
      (if exit-message
        (exit (if ok? 0 1) exit-message)
        (let [{:keys [task options] :as parsed} (parse-task-args arg-map)
              start (Date.)
              task-args (merge parsed options)
              task-fn (get tasks task)
              task-results (task-fn task-args)]
          (println "Took" (elapsed start (Date.)) "seconds")
          (if (:errors task-results)
            (exit 1 (str "Task: " task " failed "))
            (exit 0 (str "Task: " task " completed."))))))
    (catch Throwable t
      (cli.error-handling/report-and-exit t))))
