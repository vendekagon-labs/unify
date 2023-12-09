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
(ns com.vendekagonlabs.unify.import.engine-test
  (:require [clojure.test :refer :all]
            [com.vendekagonlabs.unify.import.engine :as sut]))


(def example-job-1
  {:unify/input-tsv-file    "processed/variants.txt"
   :id                  "var.id"
   :genomic-coordinates "gc.id"
   :gene                "hugo"
   :ref-allele          "ref"
   :alt-allele          "alt"})

(def example-job-2
  {:unify/input-tsv-file    ["processed/" "cnv_ref_*.tsv"]
   :unify/na            ""
   :genomic-coordinates "gc.id"
   :id                  "gc.id"
   :genes               {:unify/many-delimiter ";"
                         :unify/many-variable  "Genes"}})

(def example-job-3
  {:unify/input-tsv-file "processed/coordinates.txt"
   :unify/na         ""
   :genes            ["gene" "value"]
   :name             "signature"})


(deftest get-req-column-names-test
  (testing "Jobs report correct columns as required names."
    (is (= #{"var.id" "gc.id" "hugo" "ref" "alt"}
           (set (sut/get-req-column-names example-job-1))))
    (is (= #{"gc.id" "Genes"}
           (set (sut/get-req-column-names example-job-2))))
    (is (= #{"gene" "value" "signature"}
           (set (sut/get-req-column-names example-job-3))))))


(comment
  (run-tests *ns*))