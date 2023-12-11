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
(ns com.vendekagonlabs.unify.cli.error-handling
  (:require [com.vendekagonlabs.unify.util.text :as text]
            [clojure.tools.logging :as log])
  (:import (java.util.concurrent ExecutionException)))


(defn exit [code msg]
  (println msg)
  (shutdown-agents)
  (System/exit code))

(defn report-errors
  "For each key and value in ex-info, report error state."
  [err-map]
  (println "-------- Error occurred during import --------")
  (doseq [[k v] err-map]
    (println "Error in: " (namespace k) \newline
             "Problem: " (name k) (text/->pretty-string v))))

(defn report-and-exit [t]
  "If anticipated error (proxied by ex-info being thrown entity) we report errors in
  a standard way and exit. Otherwise, we terminate with re-throw (stack trace and all)"
  (log/info ::stack-trace (.printStackTrace t))
  (if-let [err-map (or (ex-data t)
                       (ex-data (and (instance? ExecutionException t)
                                     (.getCause t))))]
    (report-errors err-map)
    (println (.getMessage t)))
  (exit 1 "Pret encountered error while executing."))
