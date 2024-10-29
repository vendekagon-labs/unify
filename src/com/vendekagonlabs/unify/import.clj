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
  (:require [clojure.tools.logging :as log]
            [cognitect.anomalies :as anomalies]
            [com.vendekagonlabs.unify.db :as db]
            [com.vendekagonlabs.unify.db.schema :as db.schema]
            [com.vendekagonlabs.unify.db.schema.cache :as schema.cache]
            [com.vendekagonlabs.unify.db.schema.compile :as compile]
            [com.vendekagonlabs.unify.db.schema.compile.metaschema :as compile.metaschema]
            [com.vendekagonlabs.unify.import.engine :as engine]
            [com.vendekagonlabs.unify.import.engine.parse.config.yaml :as parse.yaml]
            [com.vendekagonlabs.unify.import.file-conventions :as file-conventions]
            [com.vendekagonlabs.unify.import.file-conventions :as conventions]
            [com.vendekagonlabs.unify.db.schema.compile.json-schema :as compile.json-schema]
            [com.vendekagonlabs.unify.import.retract :as retract]
            [com.vendekagonlabs.unify.import.tx-data :as tx-data]
            [com.vendekagonlabs.unify.import.upsert-coordination :as upsert-coord]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [com.vendekagonlabs.unify.util.text :refer [->pretty-string folder-of]]
            [com.vendekagonlabs.unify.validation.post-import :as post-import]))

(defn validate
  [{:keys [working-directory
           database] :as _args}]
  (let [dataset-name (conventions/dataset-name working-directory)
        db-info (db/fetch-info database)]
    (post-import/run-all-validations db-info dataset-name)))

(defn- read-config-file
  [file-path]
  (let [extension (util.io/file-extension file-path)]
    (case extension
      ".yaml" (parse.yaml/read-config-file file-path)
      ".yml" (parse.yaml/read-config-file file-path)
      ".edn" (util.io/read-edn-file file-path)
      (throw (ex-info (str "Config file " file-path " is not edn (.edn) or yaml (.yaml, .yml).")
                      {:cli/invalid-config-file {:unify/config-file ::invalid-file-type}})))))


(defn prepare-import
  "Create the txn data files from an import-config-file, datomic-config, and target-dir."
  [{:keys [target-dir
           import-cfg-file
           schema-directory
           tx-batch-size
           resume
           continue-on-error]}]
  (schema.cache/encache schema-directory)
  (let [import-config (read-config-file import-cfg-file)
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
    (db.schema/copy-schema-dir! schema-directory
                                (file-conventions/working-dir-schema-dir target-dir))
    (log/info (str "Data files prepared: \n" (->pretty-string (map #(get-in % [:job :unify/input-tsv-file]) import-result))
                   "TX data prepared: \n " (->pretty-string txn-data-pre-process)))
    (if-let [errors (seq (filter ::anomalies/category import-result))]
      {:errors errors}
      import-result)))

(defn transact-import
  "Process txn data files into Datomic from target-dir, datomic-config, and tx-batch-size.
  If update is true then diff transaction files are used during this import."
  [{:keys [target-dir
           datomic-uri
           resume
           skip-annotations
           disable-remote-calls]}]
  (println "Transacting prepared tx-data from directory:\n" target-dir "\ninto datomic db at:\n" datomic-uri)
  (let [ensured-datomic-config (db/ensure-db target-dir datomic-uri)
        tx-result-map (tx-data/transact-import-data! target-dir
                                                     ensured-datomic-config
                                                     ;; TODO this shouldn't be
                                                     ;; linked to available processors, this is
                                                     ;; an io bound operation, isn't it?
                                                     ;; does this value even get respected?
                                                     (+ 2 (.. Runtime
                                                              getRuntime
                                                              availableProcessors))
                                                     {:resume               resume
                                                      :skip-annotations     skip-annotations
                                                      :disable-remote-calls disable-remote-calls})
        {:keys [ref-results data-results]} tx-result-map]
    (if-let [anomalies (seq (concat (filter ::anomalies/category ref-results)
                                    (filter ::anomalies/category data-results)))]
      (do (log/error "Import did not complete successfully (see logs for full report): "
                     (->pretty-string anomalies))
          {:errors anomalies})
      {:results (apply merge-with + (concat ref-results data-results))})))

(defn compile-schema
  "Given an edn file that conforms to the Unify schema definition specification, will
  create a schema directory with the schema.edn, metamodel.edn, and enums.edn that fulfill
  the requested schema as per the spec."
  [{:keys [schema-directory unify-schema]}]
  (let [unify-schema-data (util.io/read-edn-file unify-schema)
        compiled-schema (compile/->raw-schema unify-schema-data)]
    (compile/validate! unify-schema-data)
    (compile/write-schema-dir! schema-directory compiled-schema)
    {:results :success}))

(defn infer-json-schema
  "Given a schema directory, infers the contents of a JSON Schema and generates
  the corresponding JSON file."
  [{:keys [schema-directory json-schema]}]
  (schema.cache/encache schema-directory)
  (let [inferred-json-schema (compile.json-schema/generate)]
    (spit json-schema inferred-json-schema)
    {:results :success}))

(defn infer-schema
  "Given a schema directory with full Datomic and Unify schema, as created by compile, attempts
  to infer a valid Unify schema. Outputs resulting file to :unify-schema specified path."
  [{:keys [schema-directory unify-schema]}]
  (schema.cache/encache schema-directory)
  (let [inferred-schema (compile/infer-schema)]
    (util.io/write-edn-file unify-schema inferred-schema)
    {:results :success}))

(defn infer-metaschema
  "Given a schema directory with full Datomic and Unify schema, as created by compile, attempts
  to generate a valid Datomic analytics metaschema. Outputs resulting file to :metaschema
  specified path."
  [{:keys [schema-directory metaschema]}]
  (schema.cache/encache schema-directory)
  (let [inferred-metaschema (compile.metaschema/generate)]
    (util.io/write-edn-file metaschema inferred-metaschema)
    {:results :success}))


(defn crosscheck-references
  "Checks all reference data in the tx-data dir of target-dir to see if it asserts anything
  about unique reference ids that differs from what's already in the database. Returns list
  of differences (if any)."
  [{:keys [target-dir datomic-uri]}]
  (let [_tx-data-dir (str target-dir "/" "tx-data")
        ensured-cfg (db/ensure-db target-dir datomic-uri)
        ref-files (conventions/ref-tx-data-filenames target-dir)]
    (mapcat (partial upsert-coord/report-upserts ensured-cfg) ref-files)))

(defn retract
  "Retracts a dataset (Datomic retractions, where the dataset remains in
  Datomic's history)"
  [{:keys [database dataset]}]
  (let [db-info (db/fetch-info database)
        retract-result (retract/retract-dataset db-info dataset)]
    (if (:completed retract-result)
      {:results retract-result}
      {:errors retract-result})))
