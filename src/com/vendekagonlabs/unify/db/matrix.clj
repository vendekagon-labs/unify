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
(ns com.vendekagonlabs.unify.db.matrix
  (:require [com.vendekagonlabs.unify.import.file-conventions :as file-conventions]
            [com.vendekagonlabs.unify.db.config :as db.config]
            [clojure.tools.logging :as log]
            [com.vendekagonlabs.unify.util.aws :as aws]
            [clojure.java.io :as io]))


(defn upload-matrix-files->s3!
  [working-dir]
  (let [matrix-files (file-conventions/matrix-filenames working-dir)]
    (doseq [matrix-key matrix-files]
      (let [src-path (file-conventions/in-matrix-dir working-dir matrix-key)]
        (log/info "Uploading matrix file: " matrix-key)
        (aws/upload-file! src-path
                          (db.config/matrix-bucket) matrix-key
                          ;; this content type must be put on s3 object or
                          ;; pre-signed url will be a pain to deal with
                          ;; from httr, possibly other clients.
                          {:ContentType "text/tab-separated-values"})))
    true))

(defn noop [_working-dir] true)

(defn copy-matrix-files!
  [working-dir]
  (let [matrix-write-dir (db.config/matrix-dir)
        _ (io/make-parents (str matrix-write-dir "/---"))
        matrix-files (file-conventions/matrix-filenames working-dir)]
    (doseq [matrix-file matrix-files]
      (let [src-path (file-conventions/in-matrix-dir working-dir matrix-file)
            dest-path (str matrix-write-dir matrix-file)]
        (io/copy src-path dest-path)))
    true))

(defn get-uploader [candel-matrix-backend]
  (case candel-matrix-backend
    "s3" upload-matrix-files->s3!
    "file" copy-matrix-files!
    "noop" noop
    :default (throw (ex-info "Not a valid matrix backend!"
                             {:matrix-backend/provided-value candel-matrix-backend
                              :matrix-backend/allowed-values #{"s3" "file" "noop"}}))))

(defn upload-matrix-files!
  [working-dir]
  (let [backend (db.config/matrix-backend)
        backend-upload-fn (get-uploader backend)]
    (backend-upload-fn working-dir)))
