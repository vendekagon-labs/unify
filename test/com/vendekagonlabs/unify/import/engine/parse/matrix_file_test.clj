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
(ns com.vendekagonlabs.unify.import.engine.parse.matrix-file-test
  (:require [com.vendekagonlabs.unify.import.engine.parse.matrix-file :as sut]
            [clojure.test :refer :all]))

(def sparse-file "test/resources/systems/candel/matrix/short-processed-counts.tsv")
(def bad-sparse-file "test/resources/systems/candel/matrix/bad-dog-no-matrix.tsv")
(def sparse-parse-directive
  {:indexed-by {"barcode" :measurement-matrix/single-cells
                "hugo"    :measurement-matrix/gene-products}
   :data-spec  :measurement/read-count
   :data-type  :db.type/long
   :sparse?    true})

(def dense-file "test/resources/systems/candel/matrix/dense-rnaseq.tsv")
(def bad-dense-file "test/resources/systems/candel/matrix/dense-wrong-col-names.tsv")
(def dense-parse-directive
  {:indexed-by {"sample.id" :measurement-matrix/samples}
   :data-spec  :measurement/read-count
   :data-type  :db.type/long
   :sparse?    false
   :target     :measurement-matrix/gene-products})

(deftest sparse_matrix_file_test
  (testing "parses file and returns expected attributes with some values."
    (let [result (sut/parse-matrix-file sparse-file sparse-parse-directive)]
      (is (seq (:measurement-matrix/gene-products result)))
      (is (seq (:measurement-matrix/single-cells result)))
      (is (not (seq (:validation-errors result))))))
  (testing "file with wrong col count throws."
    ;; highlighted in IntelliJ b/c Cursive is too dumb to understand special test
    ;; macro forms like `thrown?` in `is`
    (is (thrown-with-msg? Exception
                          #"columns"
                          (sut/parse-matrix-file
                            bad-sparse-file
                            sparse-parse-directive)))))

(deftest dense_matrix_file_test
  (testing "parses file and returns expected attributes with some values."
    (let [result (sut/parse-matrix-file dense-file dense-parse-directive)]
      (is (seq (:measurement-matrix/gene-products result)))
      (is (seq (:measurement-matrix/samples result)))
      (is (not (seq (:validation-errors result))))))
  (testing "file with wrong col name throws."
    ;; highlighted in IntelliJ b/c Cursive is too dumb to understand special test
    ;; macro forms like `thrown?` in `is`
    (is (thrown-with-msg? Exception
                          #"columns"
                          (sut/parse-matrix-file
                            bad-dense-file
                            sparse-parse-directive))))
  (let [broken-matrix-directive (assoc dense-parse-directive
                                  :data-spec :measurement/percent-of-total-cells)]
    (is (seq (:validation-errors (sut/parse-matrix-file
                                   dense-file
                                   broken-matrix-directive))))))

(comment
  (run-tests *ns*))
