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
(ns com.vendekagonlabs.unify.import.engine.parse.mapping-test
  (:require [clojure.test :refer :all]
            [com.vendekagonlabs.unify.import.engine.parse.mapping :as sut]))

(def mappings-entry
  {:unify/mappings  {:enum/yes-no       {true  ["Y" "y"]
                                         false ["N" "n"]}
                     :enum/recist       {:clinical-observation.recist/CR "CR"
                                         :clinical-observation.recist/PR "PR"
                                         :clinical-observation.recist/PD ["PD"]
                                         :clinical-observation.recist/SD ["SD"]}
                     :ref/timepoint     {"Ipilimumab/pre-treatment" ["preCTLA4"]
                                         "Ipilimumab/treatment"     ["onCTLA4"]
                                         "Nivolumab/pre-treatment"  ["postCTLA4_prePD1"]
                                         "Nivolumab/treatment"      ["onPD1"]
                                         "Nivolumab/post-treatment" ["postPD1"]}
                     :enum/variant.type {:variant.type/del ["D" "ID"]
                                         :variant.type/ins ["I"]
                                         :variant.type/snp ["SNP"]}}
   :unify/variables {:subject/dead                :enum/yes-no
                     :subject/smoker              :enum/yes-no
                     :clinical-observation/recist :enum/recist
                     :variant/type                :enum/variant.type
                     :sample/timepoint            :ref/timepoint}})

(deftest mapping-basic-test
  (let [result (sut/mappings-edn->lookup mappings-entry)]
    (testing "Correct values end up as keys."
      (is (= (keys (:unify/variables mappings-entry))
             (keys result))))
    (testing "Single values end up as keys in sub-map"
      (is (contains? (:clinical-observation/recist result) "CR")))
    (testing "Collection values all are keys in sub-map"
      (is (every? #(contains? (:variant/type result) %)
                  ["D" "ID" "SNP"])))))


(comment
  (run-tests *ns*)
  (sut/mappings-edn->lookup mappings-entry))
