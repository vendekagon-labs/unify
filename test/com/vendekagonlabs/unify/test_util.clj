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
(ns com.vendekagonlabs.unify.test-util
  (:require [clojure.edn :as edn]
            [clojure.data.csv :as data.csv]
            [clojure.java.io :as io]
            [com.vendekagonlabs.unify.db.schema :as schema]
            [com.vendekagonlabs.unify.db.schema.cache :as cache]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [com.vendekagonlabs.unify.import :as import]))

(def cached-schema-file
  ".unify/cached-schema.edn")

(def candel-schema-dir
  "test/resources/systems/candel/template-dataset/schema")

(defn get-candel-schema []
  (if (util.io/exists? cached-schema-file)
    (schema/get-metamodel-and-schema)
    (do
      (cache/encache candel-schema-dir)
      (schema/get-metamodel-and-schema))))

(defmacro thrown->ex-data
  [body]
  `(try
     ~body
     (catch Exception e#
       (ex-data e#))))

(defn ensure-filepath!
  "Ensures file can be written to."
  [filepath]
  (io/make-parents filepath)
  filepath)

(defn file->edn-seq
  "test util eagerly reads multiple forms from an edn file with no containing form"
  [f]
  (with-open [rdr (io/reader f)]
    (into [] (map edn/read-string (line-seq rdr)))))

(defn data-file-head [f n]
  (with-open [rdr (io/reader f)]
    (let [csv-stream (data.csv/read-csv rdr :separator \tab)]
      {:header (first csv-stream)
       :data   (into [] (take n (rest csv-stream)))})))

(defn bootstrap-genes []
  (let [candel-ref-data-dir "test/resources/systems/candel/reference-data"
        ref-data-index (io/file candel-ref-data-dir "index.edn")
        ref-data (util.io/read-edn-file ref-data-index)]
    (->> ref-data-index
         (util.io/read-edn-file)
         (filter #(= :genes (:name %)))
         (first))))

(defn run-import
  "Runs a complete, end-to-end import of unify data. Wrapper for tests only."
  [arg-map]
  (let [_prepare-result (import/prepare-import arg-map)
        tx-result (import/transact-import arg-map)]
    tx-result))