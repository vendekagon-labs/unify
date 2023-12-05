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
(ns com.vendekagonlabs.unify.matrix
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.vendekagonlabs.unify.db.matrix :as db.matrix]
            [com.vendekagonlabs.unify.import.file-conventions :as file-conventions]))


(defn copy-matrix-file!
  "Copies matrix file at `src-path` to `dest-path` with col names in headers
  replaced by substitution map `smap` which should define a lookup from user provided
  column names to schema defined column names. Returns `true` if matrix file
  copy succeeds, otherwise throws."
  [src-path dest-path hdr-smap constant-columns]
  (with-open [src-rdr (io/reader src-path)
              writer (io/writer dest-path)]
    (let [in-lines (line-seq src-rdr)
          hdr-str (first in-lines)
          new-hdr (loop [hdr-so-far hdr-str
                         rewrites (->> (for [[str-col-name schema-kw] hdr-smap]
                                         [str-col-name (str schema-kw)]))]
                    (if-not (seq rewrites)
                      hdr-so-far
                      (let [[replace-col with-col] (first rewrites)
                            partial-replace (str/replace hdr-so-far
                                                         (re-pattern replace-col)
                                                         with-col)]
                        (recur partial-replace (rest rewrites)))))
          constant-keys (map first constant-columns)
          constant-vals (map second constant-columns)]
      (binding [*out* writer]
        (print new-hdr)
        (if constant-columns
          (println (apply str \tab (interpose \tab constant-keys)))
          (println))
        (doseq [line (rest in-lines)]
          (print line)
          (doseq [val constant-vals]
            (print \tab val))
          (println)))
      true)))


(defn prepare-matrix-file!
  [working-dir src-mtx-path matrix-key hdr-smap constant-data]
  (let [target-path (file-conventions/in-matrix-dir working-dir matrix-key)
        target-file (io/file target-path)]
    (io/make-parents target-file)
    (copy-matrix-file! src-mtx-path target-path hdr-smap constant-data)
    true))


(defn upload-matrix-files! [working-dir]
  (db.matrix/upload-matrix-files! working-dir))

(comment

  (apply str  \tab (interpose \tab ["blah" "blah"]))
  (copy-matrix-file! "test/resources/matrix/short-processed-counts.tsv"
                     "/Users/bkamphaus/scratch/crap-matrix.tsv"
                     {"barcode" :measurement-matrix/cell-populations
                      "hugo" :measurement-matrix/gene-products
                      "count" :measurement/read-count}
                     nil))
