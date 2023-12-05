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
(ns com.vendekagonlabs.unify.import.tx-data-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [com.vendekagonlabs.unify.import.tx-data :as sut]
            [com.vendekagonlabs.unify.test-util :as util]
            [com.vendekagonlabs.unify.util.uuid :as uuid]))

(def flat-measurement-entity-maps-path "test/resources/flat_measurement_entity_data.edn")

(deftest process-one-file-test
  (let [test-output-dir (str "tmp-test-" (uuid/random))
        import-config "process-one-file-test"
        out-filepath (->> "measurement-txes.edn"
                          (str test-output-dir "/"))]
    (try
      (testing "Produces a file containing valid tx data"
        (sut/process-one-file!
          import-config
          flat-measurement-entity-maps-path
          (util/ensure-filepath! out-filepath)
          3)
        (let [results (util/file->edn-seq out-filepath)
              metadata (map first results)]
          (is (every? (partial s/valid? ::sut/valid-tx-data) results))
          (testing "And contains valid metadata as the first tx item per batch."
            (is (every? (partial s/valid? ::sut/metadata) metadata)))))
      (finally
        (util.io/delete-recursively test-output-dir)))))

(comment
  (run-tests *ns*))
