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
    "UNIFY_AWS_REGION"
    "unify.awsRegion"
    "us-east-1"))

(def ddb-table
  (wrap-config
    "UNIFY_DDB_TABLE"
    "unify.ddbTable"
    "unify-prod"))

(def reference-data-bucket
  (wrap-config
    "UNIFY_REFERENCE_DATA_BUCKET"
    "unify.referenceDataBucket"
    "unify-processed-reference-data-prod"))

(def matrix-bucket
  (wrap-config
    "UNIFY_MATRIX_BUCKET"
    "unify.matrixBucket"
    "unify-matrix"))

(def matrix-dir
  (wrap-config
    "UNIFY_MATRIX_DIR"
    "unify.matrixDir"
    "matrix-store"))

(def matrix-backend
  (wrap-config
    "UNIFY_MATRIX_BACKEND"
    "unify.matrixBackend"
    "file"))

(defn base-uri []
  (wrap-config
    "UNIFY_BASE_URI"
    "unify.baseURI"
    "datomic:dev://localhost:4334/"))
