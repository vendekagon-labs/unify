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
(ns com.vendekagonlabs.unify.import.file-conventions
  (:require [clojure.java.io :as io]
            [com.vendekagonlabs.unify.util.text :as text]
            [com.vendekagonlabs.unify.util.io :as pio]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(def ref-file-prefix "unify-ref-")
(def import-cfg-job-file-name "import-job.edn")
(def ignored-filenames #{"import-summary.edn" import-cfg-job-file-name})
(def sep (java.io.File/separator))

(defn ->full-path [fname]
  (-> fname
      (io/file)
      (.getCanonicalPath)))

(defn- edn+file-filter
  [dir]
  (->> dir
       (io/file)
       (.list)
       (filter #(str/ends-with? % ".edn"))
       (remove ignored-filenames)))

(defn- tsv+file-filter
  [dir]
  (->> dir
       (io/file)
       (.list)
       (filter #(str/ends-with? % ".tsv"))
       (remove ignored-filenames)))

(defn rm-edn-files
  [dir]
  (let [files (->> dir
                   (io/file)
                   (.list)
                   (filter #(str/ends-with? % ".edn")))]
    (doseq [f files]
      (-> (io/file (str dir sep f))
          (.delete)))))

(defn tx-data-folder
  "Return the tx data folder path within a target dir."
  [target-dir]
  (str target-dir sep "tx-data"))

(defn matrix-folder
  "Return the matrix folder path within a target dir."
  [target-dir]
  (str target-dir sep "matrix-data"))

(defn diff-dir
  "Returns the folder path for the update transaction data"
  [target-dir]
  (str (tx-data-folder target-dir) sep "diff"))

(defn working-dir-schema-dir
  "Returns the folder path for the schema-dir cached in the working
  directory."
  [target-dir]
  (str target-dir sep "schema"))

(defn diff-tx-dir [target-dir]
  (let [f (str target-dir sep "tx-data" sep "diff" sep "tx-data")]
    (pio/mkdirs! f)
    (.getCanonicalPath (io/file f))))

(defn in-entity-dir [target-dir fname]
  (let [target-full-path (-> target-dir
                             (io/file)
                             (.getCanonicalPath))]
    (str target-full-path sep fname)))

(defn diff-summary-file [target-dir]
  (str (-> target-dir
           (io/file)
           (.getCanonicalPath)
           diff-dir)
       sep
       "diff-summary.edn"))

(defn in-diff-tx-dir [target-dir fname]
  (str (diff-tx-dir target-dir) sep fname))


(defn in-tx-data-dir [target-dir fname]
  (let [target-full-path (-> target-dir
                             (tx-data-folder)
                             (io/file)
                             (.getCanonicalPath))]
    (str target-full-path sep fname)))

(defn in-matrix-dir [target-dir fname]
  (let [target-full-path (-> target-dir
                             (matrix-folder)
                             (io/file)
                             (.getCanonicalPath))]
    (str target-full-path sep fname)))

(defn import-cfg-job-path [target-dir]
  (-> target-dir
      (io/file)
      (.getCanonicalPath)
      (str sep import-cfg-job-file-name)))

(defn tx-import-cfg-job-path
  [target-dir]
  (-> target-dir
      (tx-data-folder)
      (import-cfg-job-path)))

(defn ref-tx-data? [fpath]
  (let [fname (text/filename fpath)]
    (str/starts-with? fname ref-file-prefix)))



(defn all-entity-filenames
  "Return a list of all filenames of .edn files in the directory 'path' excluding
  the import-summary.edn and import-job.edn files"
  [target-dir]
  (->> (edn+file-filter target-dir)
       (map (partial str target-dir sep))
       (map ->full-path)))

(defn all-tx-data-filenames
  "Return a list of all filenames of .edn files in the directory 'path' excluding
  the import-summary.edn and import-job.edn files"
  [target-dir]
  (let [tx-data-dir (tx-data-folder target-dir)]
    (->> (edn+file-filter tx-data-dir)
         (map (partial str tx-data-dir sep))
         (map ->full-path))))

(defn dataset-tx-data-filenames
  "Return a list of all filenames of .edn files in the directory 'path' excluding
  the import-summary.edn file and any files that begin with unify-ref"
  [target-dir]
  (->> target-dir
       (all-tx-data-filenames)
       (remove ref-tx-data?)))

(defn ref-tx-data-filenames
  "Return a list of all filenames of .edn files that begin with 'unify-ref-'"
  [target-dir]
  (->> target-dir
       (all-tx-data-filenames)
       (filter ref-tx-data?)))

(defn matrix-filenames
  "Given a unify working directory, returns a list of all matrix filenames."
  [target-dir]
  (->> target-dir
       (matrix-folder)
       (tsv+file-filter)))

(defn job-entity [target-dir]
  (let [in-path (str target-dir sep "tx-data" sep import-cfg-job-file-name)]
    (edn/read-string (str "[" (slurp in-path) "]"))))

(defn import-name
  "Return the import job name defined by the import job file in the tx-data
   subdir of the given path."
  [target-dir]
  (let [data (job-entity target-dir)
        import-job-name (get-in (ffirst data) [:import/import :import/name])]
    import-job-name))

(defn dataset-name
  "Return the dataset name defined by the import job file in the given path."
  [target-dir]
  (let [data (job-entity target-dir)
        dataset-name (:dataset/name (second data))]
    dataset-name))


(defn dataset-name
  "Return the dataset name (from the import entity tx-data file)"
  [target-dir]
  (let [in-path (str target-dir sep "tx-data" sep import-cfg-job-file-name)
        cfg-file-data (edn/read-string (str "[" (slurp in-path) "]"))]
    (->> cfg-file-data
         (second)
         (filter :dataset/name)
         (first)
         (:dataset/name))))

(comment
  (import-cfg-job-path "tmp-now")
  (tx-import-cfg-job-path "tmp-now")
  (all-tx-data-filenames "tmp-now")
  (ref-tx-data-filenames "tmp-sanity-test-output")
  (dataset-tx-data-filenames "tmp-sanity-test-output")
  (all-entity-filenames "tmp-now"))
