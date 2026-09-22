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
(ns com.vendekagonlabs.unify.util.memo
  "A memoize variant whose caches can be explicitly cleared.

  Several hot-path functions in this codebase are memoized on arguments that
  include large structures rebuilt fresh (but structurally equal) on every
  prepare run within a process -- e.g. the parsed config tree, or the
  compiled schema/metamodel. clojure.core/memoize's cache lives for the life
  of the process: on the *second* prepare run in a process, every lookup for
  a previously-seen-shaped key has to walk a hash bucket containing the
  first run's now-stale entries and do a full recursive structural equality
  check to prove non-collision, before falling through to compute (and cache)
  the answer. Measured impact: process CPU time up ~14x for identical work,
  and wall-clock up ~10x, on the second+ prepare run in a process (this
  includes, e.g., successive test namespaces sharing one test JVM).

  resettable-memoize registers its cache so reset-all-caches! can clear every
  such cache at once -- call it at the start of every prepare run.")

(defonce ^:private caches (atom []))

(defn resettable-memoize
  "Like clojure.core/memoize, but the returned fn's cache is registered so
  reset-all-caches! can clear it."
  [f]
  (let [cache (atom {})]
    (swap! caches conj cache)
    (fn [& args]
      (if-let [e (find @cache args)]
        (val e)
        (let [ret (apply f args)]
          (swap! cache assoc args ret)
          ret)))))

(defn reset-all-caches!
  "Clears every cache created via resettable-memoize. Call at the start of
  each prepare run, before any resettable-memoize'd fn is used."
  []
  (doseq [cache @caches]
    (reset! cache {})))
