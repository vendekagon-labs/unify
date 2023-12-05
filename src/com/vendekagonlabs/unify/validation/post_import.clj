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
(ns com.vendekagonlabs.unify.validation.post-import
    "Post-import validations include database-wide query constraints and per-entity
    validations that require joins across tables (imported records) for fulfillment.
    This is also where UID reference validity is checked, confirming that reference
    resolution did not result in otherwise empty entities, etc."
  (:require [com.vendekagonlabs.unify.validation.post-import.util :as vutil]
            [clojure.core.async :as a]
            [com.vendekagonlabs.unify.validation.post-import.query :as query]))

(def errors
  (atom []))

(defn run-all-validations
  "Runs all post-import validations against the db specified in db-uri.
  Prints validation results"
  [db-info dataset-name]
  (println "\nRunning validations for entities in dataset: " dataset-name)
  (let [data-ch (a/to-chan!! (vutil/data-validations db-info dataset-name))
                       ;; add -group by logic- and nest take in there
        ref-ch  (a/to-chan!! (vutil/ref-data-validations db-info))
        validation-ch (a/merge [data-ch ref-ch])
        xf identity
        to-ch (a/chan 1024)
        done-ch (a/chan)]
    (a/go-loop [total 0]
      (when (zero? (mod total 1000))
        (print ".") (flush))
      (if-let [ent (a/<! to-ch)]
        (do
          (when-let [ent-errors (:unify.validation/errors ent)]
            (doseq [err ent-errors]
              (println err))
            ;; TODO: nicer validation error groupings and formatting TBD
            (when (< (count @errors) 1000)
              (swap! errors conj ent)))
          (recur (inc total)))
        (a/>! done-ch {:completed total})))
    (a/pipeline-blocking 40 to-ch xf validation-ch)
    (a/<!! done-ch))
  @errors)


(comment
  (run-all-validations
    {:uri "datomic:ddb://us-east-1/pici-candel-prod-right/pici0002-ph2-33"}
    "pici0002"))
