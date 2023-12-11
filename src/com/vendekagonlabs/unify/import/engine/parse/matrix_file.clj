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
(ns com.vendekagonlabs.unify.import.engine.parse.matrix-file
  (:require [clojure.data.csv :as csv]
            [clojure.spec-alpha2 :as s]
            [clojure.java.io :as jio]
            [com.vendekagonlabs.unify.import.engine.parse.data :as parse.data]))

(defn- only
  [s message]
  (let [one (first s)
        more (rest s)]
    (if-not (seq more)
      one
      (throw (ex-info (str "More than one value when only one expected!\n"
                           message)
                      {::expected-one  one
                       ::more-than-one more})))))



(defn parse-matrix-file
  "Given a file-path and map of arguments, parse-matrix-file will read
  a matrix file, parsing its indices into sets and checking each matrix
  value against a provided spec.

  Args:
  :indexed-by A map of colnames to unify schema attributes, e.g. {'colname' :measurement/value}
  :value-spec A spec which all matrix values (i.e. not indices) much conform to.
  :sparse?    Set to true if sparse, otherwise leave nil or set false.
  :target     Must be supplied when `sparse?` is false or nil. Which id attribute corresponds
              to non-index column names.
  "
  [file-path {:keys [indexed-by data-spec data-type sparse? target] :as matrix-job}]
  (with-open [rdr (jio/reader file-path)]
    (let [csv-seq (csv/read-csv rdr :separator \tab)
          colnames (first csv-seq)
          indices (keys indexed-by)
          row-seq (rest csv-seq)]
      ;; all matrices should have more cols than indices
      (if (or (> (count indices)
                 (count colnames))
              ;; sparse matrices have exactly one additional column w/values
              (and sparse?
                   (not= (count colnames)
                         (inc (count indices)))))
        (throw (ex-info "File columns and index columns specified do not result in a valid matrix."
                        {::indices  indices
                         ;; since this is a >, this should always be small but if there's
                         ;; a chance it's large, we might want to truncate w/take for
                         ;; legibility of error.
                         ::colnames colnames}))
        (let [ind-pos (apply merge (for [ind-col indices]
                                     {ind-col
                                      (only (keep-indexed (fn [i val]
                                                            (when (= ind-col val)
                                                              i))
                                                          colnames)
                                            (str "Too many matches for index! " ind-col))}))
              pos->colname (into {} (for [[ind-col ind-pos'] ind-pos]
                                      [ind-pos' ind-col]))
              target-inds (vals ind-pos)
              non-ind-cols (remove (set indices) colnames)
              coerce-fn (let [coerce (get parse.data/string->datatype data-type)]
                          (fn [s-val]
                            (if (#{"NA"} s-val)
                              ::na
                              (coerce s-val))))
              valid? (if-let [data-type-spec (s/get-spec data-spec)]
                       (fn [scalar-entry]
                         (try
                           (s/valid? data-type-spec scalar-entry)
                           (catch Exception e
                             false)))
                       (fn [_] true))]
          (loop [rows row-seq
                 index-sets (zipmap target-inds (repeat #{}))
                 validation-errors '()]
            (let [this-row (first rows)
                  row-as-map (zipmap (range) this-row)]
              (if-not this-row
                (merge (into {} (for [[key-by-pos index-vals] index-sets]
                                  (let [str-colname (get pos->colname key-by-pos)
                                        attr-colname (get indexed-by str-colname)]
                                    [attr-colname index-vals])))
                       {:validation-errors validation-errors}
                       (when target
                         {target non-ind-cols})
                       (when sparse?
                         {:unify.matrix/sparse-value-column (only non-ind-cols
                                                                  (str "Too many measurement columns for sparse matrix: "
                                                                       non-ind-cols))}))
                (recur (rest rows)
                       (merge-with conj index-sets
                                   (select-keys row-as-map target-inds))
                       (let [matrix-field-only (vals (apply dissoc row-as-map target-inds))
                             invalid-entries (->> matrix-field-only
                                                  (map coerce-fn)
                                                  (remove #{::na})
                                                  (remove valid?))]
                         (if (seq invalid-entries)
                           ;; to keep this from blowing up, we limit it to first 1000 invalid entries.
                           ;; -- punting on early termination here, but we could also wire that in with
                           ;; -- a count test.
                           (take 1000 (concat invalid-entries validation-errors))
                           validation-errors)))))))))))
