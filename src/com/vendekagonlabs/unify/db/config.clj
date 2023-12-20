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


(defn resolve-config
  [env property default]
  (or (System/getenv env)
      (System/getProperty property)
      default))

(defn aws-region []
  (resolve-config
    "UNIFY_AWS_REGION"
    "unify.awsRegion"
    "us-east-1"))

(defn ddb-table []
  (resolve-config
    "UNIFY_DDB_TABLE"
    "unify.ddbTable"
    "unify-prod"))

(defn reference-data-bucket []
  (resolve-config
    "UNIFY_REFERENCE_DATA_BUCKET"
    "unify.referenceDataBucket"
    "unify-processed-reference-data-prod"))

(defn matrix-bucket []
  (resolve-config
    "UNIFY_MATRIX_BUCKET"
    "unify.matrixBucket"
    "unify-matrix"))

(defn matrix-dir []
  (resolve-config
    "UNIFY_MATRIX_DIR"
    "unify.matrixDir"
    "matrix-store"))

(defn matrix-backend []
  (resolve-config
    "UNIFY_MATRIX_BACKEND"
    "unify.matrixBackend"
    "file"))

(defn base-uri []
  (resolve-config
    "UNIFY_BASE_URI"
    "unify.baseURI"
    "datomic:dev://localhost:4334/"))
