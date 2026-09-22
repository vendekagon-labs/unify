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
(ns perf-bench
  "Ad-hoc benchmark harness for the `prepare` step (row parsing -> entity/map
  formation), isolated from `transact` since the transactor is the fixed,
  saturating bottleneck there and not something local perf work can move.

  Uses the CANDEL template-dataset fixture (the same one exercised by
  com.vendekagonlabs.unify.import-test's integration test), since it's the
  largest realistic dataset checked into the repo (~150k data rows).

  Usage:
    clojure -M:dev -e \"(require 'perf-bench) (perf-bench/-main 5)\"
  or from a REPL:
    (require 'perf-bench)
    (perf-bench/-main 5)"
  (:require [com.vendekagonlabs.unify.db.schema.cache :as cache]
            [com.vendekagonlabs.unify.db.schema :as schema]
            [com.vendekagonlabs.unify.import.engine :as engine]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [com.vendekagonlabs.unify.util.text :as text]))

(def schema-dir "test/resources/systems/candel/template-dataset/schema")
(def cfg-file "test/resources/systems/candel/template-dataset/config.edn")
;; matches import/prepare-import's own (str (folder-of import-cfg-file) "/") exactly,
;; since path-prefixing logic downstream assumes an absolute root dir.
(def cfg-root-dir (str (text/folder-of cfg-file) "/"))
(def out-root "tmp-perf-output")

(defn run-once
  "Runs the full prepare (entity generation) step once against the template
  dataset, writing output to a fresh target-dir, and returns elapsed ms."
  [target-dir]
  (when (util.io/exists? target-dir)
    (util.io/delete-recursively target-dir))
  (let [sch (schema/get-metamodel-and-schema)
        cfg (util.io/read-edn-file cfg-file)
        start (System/nanoTime)]
    (engine/create-entity-data sch cfg cfg-root-dir target-dir false false)
    (/ (- (System/nanoTime) start) 1e6)))

(defn -main
  ([] (-main 5))
  ([n]
   (cache/encache schema-dir)
   ;; warmup run, JITs hot loop, not counted.
   (println "warmup...")
   (run-once (str out-root "-warmup"))
   (let [times (vec (for [i (range n)]
                       (let [t (run-once (str out-root "-" i))]
                         (println (format "run %d: %.1f ms" i (double t)))
                         t)))
         mean (/ (reduce + times) (count times))]
     (println (format "\nmean: %.1f ms  min: %.1f ms  max: %.1f ms  (n=%d)"
                       (double mean) (double (apply min times)) (double (apply max times)) n))
     mean)))
