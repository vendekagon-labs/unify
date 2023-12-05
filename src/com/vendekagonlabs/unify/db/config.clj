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
(ns com.vendekagonlabs.unify.db.config)


(defn wrap-config
  [env property default]
  (fn []
    (or (System/getenv env)
        (System/getProperty property)
        default)))


(def aws-region
  (wrap-config
    "CANDEL_AWS_REGION"
    "candel.awsRegion"
    "us-east-1"))

(def ddb-table
  (wrap-config
    "CANDEL_DDB_TABLE"
    "candel.ddbTable"
    "candel-prod"))

(def reference-data-bucket
  (wrap-config
    "CANDEL_REFERENCE_DATA_BUCKET"
    "candel.referenceDataBucket"
    "unify-processed-reference-data-prod"))

(def matrix-bucket
  (wrap-config
    "CANDEL_MATRIX_BUCKET"
    "candel.matrixBucket"
    "candel-matrix"))

(def matrix-dir
  (wrap-config
    "CANDEL_MATRIX_DIR"
    "candel.matrixDir"
    "matrix-store"))

(def matrix-backend
  (wrap-config
    "CANDEL_MATRIX_BACKEND"
    "candel.matrixBackend"
    "s3"))

;; don't default base-uri, only used to override, nil puna/when check for
;; conrol flow when not present
(defn base-uri []
  (System/getenv "CANDEL_BASE_URI"))
