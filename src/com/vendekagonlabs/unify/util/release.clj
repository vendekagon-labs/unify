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
(ns com.vendekagonlabs.unify.util.release
  (:require [clojure.edn :as edn]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [clojure.java.io :as io]))

(defn version
  "Reads info.edn to retrieve version info."
  []
  (let [info-edn (io/resource "info.edn")]
    (when-not info-edn
      (throw
        (ex-info (str "info.edn file does not exist, you should only encounter this error as a dev in unify repo."
                       "\nRegenerate with echo command below or see Makefile for reference:"
                      "\n----------------------\n"
                      "> echo \"{:unify/version \\\"$(clj -X:version)\\\"}\" > resources/info.edn"
                      "\n----------------------\n"
                      "If you see this error as an end user, report to Unify dev team.")
                 {:cli/missing-file "resources/info.edn"})))
    (:unify/version (util.io/read-edn-file info-edn))))
