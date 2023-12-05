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
(ns com.vendekagonlabs.unify.bootstrap.data.drugs
  "This namespace contains utilities for processing the WHO Standard Drug
  Groupings data into CANDEL schema. The information in these files is
  proprietary and cannot be redistributed."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [com.vendekagonlabs.unify.util.io :as util.io]))

(defn- parse-drug-code
  [s]
  {:rec-num (subs s 0 6)
   :seq-1   (subs s 6 8)
   :seq-2   (subs s 8 11)})

(defn- preferred-name?
  "Returns true if this drug code is associated with the preferred name of a drug"
  [drug-rec-num]
  (and (= (:seq-1 drug-rec-num) "01") (= (:seq-2 drug-rec-num) "001")))

(defn- load-sdg-groups
  [file]
  (with-open [reader (io/reader file)]
    (let [lines (line-seq reader)]
      (into {} (map #(vector (subs % 0 5)
                             (string/trim (subs % 5 105))) lines)))))

(defn- load-drugs->sdg-groups
  [sdg-groups-file sdg-drugs-file]
  (let [sdg-groups (load-sdg-groups sdg-groups-file)]
    (with-open [reader (io/reader sdg-drugs-file)]
      (let [lines (line-seq reader)
            xf (comp
                 (map #(let [drug-code (parse-drug-code (subs % 0 11))]
                         (if (preferred-name? drug-code)
                           [(:rec-num drug-code) (sdg-groups (subs % 1513 1518))]
                           [])))
                 (filter #(not= (count %) 0)))]
        (into {} xf lines)))))

(defn- process-drug-dictionary-line
  [s x]
  (let [drug-code (parse-drug-code (subs s 0 11))
        drug-name (string/trim (subs s 30 1530))] ; trim ws
    (as-> (x (:rec-num drug-code) {:drug/record-number (:rec-num drug-code)
                                   :drug/variations []}) m
          (if (preferred-name? drug-code)
            (assoc m :drug/preferred-name drug-name)
            (update-in m [:drug/variations] #(conj % drug-name)))
          (assoc x (:rec-num drug-code) m))))

(defn- load-drugs
  [file]
  (with-open [reader (io/reader file)]
    (loop [x {}
           lines (line-seq reader)]
      (if (seq lines)
        (let [m (process-drug-dictionary-line (first lines) x)]
          (recur m (rest lines)))
        (vals x)))))

(defn init
  "Returns transaction data for drugs entities for db initialization. opts is a map
  with the following keys
    :drugs-b3-file    The path to the file (in B3 format) containing the drug dictionary
                       (the DD.txt file)
    :sdg-groups-file  The path to the file containing the groups definition for the Standardised
                       Drugs Groupings dataset (the SDG.txt file)
    :sdg-drugs-file   The path to the file associating individual drugs with SDG groups
                       (the SDGContent.txt file)"
  [drugs-b3-file sdg-groups-file sdg-drugs-file]
  (let [drugs->sdg-groups (load-drugs->sdg-groups sdg-groups-file sdg-drugs-file)
        drugs (load-drugs drugs-b3-file)]
    (map #(if-let [sdg-group (drugs->sdg-groups (% :drug/record-number))]
            (assoc % :drug/sdg-group sdg-group)
            %) drugs)))

(defn generate-tx-data
  [{:keys [drugs-b3-file sdg-groups-file sdg-drugs-file output-file]}]
  (let [drug-data (init drugs-b3-file sdg-groups-file sdg-drugs-file)]
    (util.io/write-tx-data drug-data output-file)))


(comment
  (generate-tx-data
    {:drugs-b3-file "seed_data/raw/drugs/DD.txt"
     :sdg-groups-file "seed_data/raw/drugs/SDG.txt"
     :sdg-drugs-file "seed_data/raw/drugs/SDGContent.txt"
     :output-file "seed_data/edn/all-drug-tx-data.edn"}))
