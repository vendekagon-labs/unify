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
(ns com.vendekagonlabs.unify.validation.record
  "For any given record, functionality, we can ensure correspondence with spec for
  an indivual attribute/value pair, if a spec is defined."
  (:require [clojure.spec-alpha2 :as s]
            ;; don't invoke directly, but this is a load-order dependency, e.g. for repl workflows.
            ;; i.e., if specs namespace not loaded, these specs won't exist.
            [cognitect.anomalies :as anom]))

(defn validate [ent-map]
  (let [ent (dissoc ent-map :unify/annotations)
        issues (seq (remove nil? (for [[a-kw val] ent]
                                   (when-let [spec (s/get-spec a-kw)]
                                     (when-not (s/valid? a-kw val)
                                       {a-kw {:failing-value val
                                              :location (:unify/annotations ent-map)
                                              :spec-failure (s/explain-str a-kw val)}})))))]
    (if-not issues
      ent-map
      {::anom/category ::anom/incorrect
       :data/attribute-value-spec-failures (vec issues)})))

(comment
  (s/get-spec :measurement/cell-population)
  (s/valid? :measurement/cell-count 2))
