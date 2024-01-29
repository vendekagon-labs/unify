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
(ns com.vendekagonlabs.unify.import.diff.tx-data
  "Generates the transaction data files that will be transacted during an
  update operation on an import."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.pprint :refer [pprint]]
            [com.vendekagonlabs.unify.db.indexes :as db.indexes]
            [com.vendekagonlabs.unify.db.schema :as db.schema]
            [com.vendekagonlabs.unify.db.metamodel :as metamodel]
            [com.vendekagonlabs.unify.import.file-conventions :as conventions]
            [com.vendekagonlabs.unify.import.tx-data :as tx-data]
            [com.vendekagonlabs.unify.import.diff.changes :as cmp]
            [com.vendekagonlabs.unify.util.timestamps :as timestamps]
            [com.vendekagonlabs.unify.util.uuid :as uuid]
            [com.vendekagonlabs.unify.db :as db]
            [clojure.edn :as edn])
  (:import (java.io PushbackReader)))

;; Ordering of Transactions ---------------------------------------------
;;
;; Generated data must be transacted in an order which ensures
;; that all dependencies of each entity exist by the time of the
;; transaction attempt.
;;
;; This ordering is provided by leveraging the metamodel:
;;
;; 1. Pull all refs by a given kind (ie. :unify.ref/from :measurement)
;; 2. Only use kind-name's that are part of the uid keys
;; 3. Count # of dependencies to 'terminating' kind in deps set
;;    Score for kind is max of all counts.
;; 4. Order from lowest -> highest
;;

(defn max-depth [refs kind-name accum]
  (let [deps (get refs kind-name)]
    (if (seq deps)
      (let [counts (reduce
                     (fn [coll kind-name]
                       (conj coll (max-depth refs kind-name (inc accum))))
                     []
                     deps)]
        (apply max counts))
      accum)))

(defn kind-depth
  "Computes the maximum number of dependency steps a given kind would
   require to reach a an entity that doesn't have dependencies.

   Uses the map of references "
  [refs kind-name]
  (max-depth refs kind-name 0))

(defn sort-by-parentage
  [schema uid-keys]
  (let [kinds (into #{} (map
                          #(keyword (namespace %))
                          uid-keys))
        refs (reduce
               (fn [coll kind-name]
                 (let [dependencies (metamodel/refs-by-kind schema kind-name)
                       filtered (filter #(and
                                           (not= kind-name %)
                                           (contains? kinds %)) dependencies)]
                   (assoc coll kind-name (into #{} filtered))))
               {}
               kinds)
        counts (reduce
                 (fn [coll attr]
                   (let [kind-name (keyword (namespace attr))]
                     (assoc coll attr (kind-depth refs kind-name))))
                 {}
                 uid-keys)]
    (->> counts
         (sort-by val <)
         keys)))

(defn sorted-uid-attrs
  "Returns a sorted list of maps that contain the
   target uid attribute and a target output file.
   The sort order respects referential dependencies
   in the transaction data via sort order of files on disk by name.

   i.e. [{:uid-attr :measurement/uid
          :output '/some/tx-data/tx-update/00-blah.edn'} ...]

  Sorting order is based on parentage from the metamodel where
  children of an attribute are done first.

  i.e. treatment-regimen -> therapy -> subject -> sample -> measurement
       -> measurement-set -> assay -> dataset

  This ensures that any referenced uid exists before an attempted
  transaction."
  []
  (let [schema (db.schema/get-metamodel-and-schema)
        uid-keys (keys (db.indexes/by-uid schema))
        sorted-attrs (sort-by-parentage schema uid-keys)]
    (map-indexed
      (fn [idx attr]
        (let [index (format "%02d" idx)]
          {:uid-attr attr
           :filename (str index "-" (namespace attr) ".edn")}))
      sorted-attrs)))



;; Transaction Creation ---------------------------------------------------
;;

(defn create-tx
  "Generates datomic tx from diff result"
  [{:keys [state] :as result}]
  (condp = (:state result)

    :new
    (:entity result)

    :changed
    [:db/add (:uid result) (:attr result) (:value result)]

    :removed
    [:db/retract (:uid result) (:attr result) (:value result)]

    :removed-entity
    [:db/retractEntity (:entity result)]))


(defn write-tx-results!
  [import-name target-dir batch filename results]
  (let [full-path (conventions/in-diff-tx-dir target-dir filename)]
    (with-open [out (io/writer full-path)]
      (doseq [data (->> results
                        (map #(create-tx %))
                        (partition-all batch)
                        (map (fn [tx-batch]
                               (conj tx-batch
                                     (tx-data/create-txn-metadata import-name)))))]
        (binding [*out* out]
          ;;(prn data)
          (pprint data))))))

(defn rename-ref-import
  [import-name ref-data-batch]
  (let [ref-data (rest ref-data-batch)]
    (cons
      (tx-data/create-txn-metadata import-name)
      (map
        (fn [ref-data]
          (assoc ref-data
            :unify.import/most-recent [:unify.import/name import-name]))
        ref-data))))

(defn rewrite-ref-file!
  [import-name src-fname dest-fname]
  (with-open [in (PushbackReader. (io/reader src-fname))]
    (let [push! (fn [data]
                  (spit dest-fname data :append true))
          input-seq (->> (repeatedly #(edn/read {:eof ::eof} in))
                         (take-while #(not= % ::eof)))]
      (doall
        (sequence
          (comp (map (partial rename-ref-import import-name))
                (map push!))
          input-seq)))))


(defn write-import-job-tx!
  "Writes out the import-job.edn file that contains the import job
   metadata."
  [import-name target-dir]
  (let [import-ent (get (ffirst (conventions/job-entity target-dir))
                        :unify.import.tx/import)
        import-tx [{:db/id         "datomic.tx"
                    :unify.import.tx/import (assoc import-ent :db/id "temp-import-ent"
                                                              :unify.import/name import-name)
                    :unify.import.tx/id (uuid/random)}]
        full-path (conventions/in-diff-tx-dir target-dir "import-job.edn")]
    (log/info "Writing diff import job to: " full-path)
    (pprint import-tx (io/writer full-path))))

(defn write-summary!
  "Writes out the diff-summary.edn file that contains:

  {:timestamp ...
   :database ...
   :import-name ...
   :diff-dataset-name ...}"
  [{:keys [target-dir database diff-dataset-name] :as args}]
  (let [full-path (conventions/diff-summary-file target-dir)
        import-name (conventions/import-name target-dir)
        db-info (db/fetch-info database)
        db (db/latest-db db-info)
        head (db/head db)
        summary {:timestamp         (java.util.Date.)
                 :database          database
                 :import-name       import-name
                 :diff-dataset-name diff-dataset-name
                 :head              head}]
    (log/info "Writing diff summary to: " full-path)
    (pprint summary (io/writer full-path))))

(defn make-transaction-data!
  "Creates transaction data for all changes in the updated dataset
   that conforms to import job expectations. Requires that
   a scratch dataset "
  [{:keys [target-dir
           tx-batch-size
           diff-suffix
           database] :as opts}]
  (let [uid-attrs (sorted-uid-attrs)
        import-name (str (conventions/import-name target-dir) "-diff-" (timestamps/now))
        dataset-name (conventions/dataset-name target-dir)
        diff-dataset-name (str dataset-name "-" diff-suffix)
        diff-opts {:dataset-name      dataset-name
                   :diff-dataset-name diff-dataset-name}
        db-info (db/fetch-info database)]

    (cmp/cache-db-info! db-info)

    (log/info "Starting diff -  import-name: " import-name
              " dataset-name: " dataset-name " suffix: " diff-suffix
              " diff-dataset-name: " diff-dataset-name)

    (write-import-job-tx! import-name target-dir)

    ;; Dependent entities
    (log/info "Generating changes from dependent entities:  " diff-dataset-name)
    (doall
      (pmap
        (fn [attr-info]
          (let [results (cmp/diff-by-attr diff-opts (:uid-attr attr-info))]
            (when (seq results)
              (write-tx-results!
                import-name
                target-dir tx-batch-size
                (:filename attr-info)
                results))))
        uid-attrs))

    (log/info "Generating removals from dependent entities:  " diff-dataset-name)
    (doall
      (pmap
        (fn [attr-info]
          (let [results (cmp/removed-entities-by-attr diff-opts (:uid-attr attr-info))]
            (when (seq results)
              (write-tx-results!
                import-name
                target-dir tx-batch-size
                (str "removed-" (:filename attr-info))
                results))))
        uid-attrs))

    ;; Dataset entity
    (log/info "Generating dataset changes: " database
              " " diff-dataset-name)
    (let [results (cmp/dataset-changes diff-opts)]
      (when (seq results)
        (write-tx-results!
          import-name
          target-dir tx-batch-size
          "dataset.edn"
          results)))

    ;; rewrite reference data
    (log/info "Moving prepared reference data into diff.")
    (let [ref-files (conventions/ref-tx-data-filenames target-dir)]
      (doall
        (pmap
          (fn [ref-data-fname]
            (let [src-fname ref-data-fname
                  sname (.getName (io/file ref-data-fname))
                  dest-fname (conventions/in-diff-tx-dir target-dir sname)]
              (rewrite-ref-file! import-name src-fname dest-fname)))
          ref-files)))))
