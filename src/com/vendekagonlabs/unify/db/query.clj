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
(ns com.vendekagonlabs.unify.db.query
  (:require [datomic.api :as d]))

(defn q+retry
  "Invoke Datomic query wrapped in retry with simple linear retry logic.

  Note: this reflects previous client implementation for query. Not strictly
  necessary to peer, but does reflect separation of concerns: individual
  callers point to common params/retry. In Datomic peer/on-prem this is managed
  for us, but if this is ever refactored to use query in client, we want to
  constrain it so that all callers use retry, etc. set here."
  [& args]
  (apply d/q args))