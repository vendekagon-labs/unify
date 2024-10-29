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
(ns com.vendekagonlabs.unify.util.io
  (:require [clojure.java.io :refer [make-parents file delete-file]]
            [clojure.edn :as edn]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as s]
            [com.vendekagonlabs.unify.util.text :refer [->pretty-string]]
            [clojure.java.io :as io]))

(defn file-extension [file-path-str]
  (-> (re-find #"(\.[a-zA-Z0-9]+)$" file-path-str)
      (second)
      (String/.toLowerCase)))

(defn delete-recursively [fname]
  (doseq [f (reverse (file-seq (file fname)))]
    (delete-file f)))

(defn mkdirs!
  "Make all directories that don't exist in absolute path (mkdir -p)"
  [dir]
  (-> dir
      (io/file)
      (.mkdirs)))

(defn exists?
  "Predicate to determine if file f (string filename or file) exists."
  [f]
  (-> (if (string? f)
        (file f)
        f)
      (.exists)))

(defn dir?
  "Predicate to determine if file f (string filename or file) exists."
  [f]
  (-> (if (string? f)
        (file f)
        f)
      (.isDirectory)))

(defn empty-dir?
  "Predicate to determine if the file f (string or file) is an empty directory."
  [f]
  (if (and (exists? f) (dir? f))
    (-> (if (string? f)
          (file f)
          f)
        (.listFiles)
        count
        zero?)
    false))

(defn write-edn-file
  "Makes parent folders (if necessary) and spits directly into f is data is a string, otherwise writes
   ->pretty-string of data."
  [f data]
  (make-parents f)
  (spit f (if (string? data)
            data
            (->pretty-string data))))


(defn glob
  "Given a directory and a glob-pattern, returns vector of matched files."
  [glob-dir glob-pattern]
  (let [grammar-matcher
        (-> (java.nio.file.FileSystems/getDefault)
            (.getPathMatcher (str "glob:" glob-pattern)))]
    (->> glob-dir
         clojure.java.io/file
         file-seq
         (filter (fn [f]
                   (and (.isFile f)
                        (let [fname (-> f (.toPath) (.getFileName))]
                          (.matches grammar-matcher fname)))))
         (mapv #(.getAbsolutePath %)))))

(defn unrealized-glob
  "Reads in a glob specification and outputs a map"
  [[dir pattern]]
  {:unify.glob/directory dir
   :unify.glob/pattern   pattern})

(defn read-edn-file
  "Reads EDN file, or throws ex-info with info on why EDN file can't be read."
  [f]
  (try
    (let [f-text (slurp f)
          f-edn (edn/read-string {:readers {'glob unrealized-glob}} f-text)]
      f-edn)
    (catch Exception e
      (let [message (.getMessage e)
            ;; this is written as cond and can be extended so that we re-map unclear errors
            ;; as encountered to better ones but let clear enough ones through via else
            cause (cond
                    (= message "EOF while reading")
                    "Unmatched delimiters in EDN file resulted in no closing ),}, or ]."

                    :else
                    message)]
        (throw (ex-info (str "Invalid EDN file: " f)
                        {:file  f
                         :cause cause}))))))

(defn write-tx-data
  "Writes the data in x to file f (overwriting if it exists). The data is partitioned
  in batches of 100"
  [x f]
  (let [part-tx-data (partition 100 100 nil (distinct x))]
    (if (exists? f)
      (io/delete-file f))
    (doseq [tx part-tx-data]
      (spit f (->> tx
                   flatten
                   (into [])) :append true))))

(defn local-tar-name
  "Vets and returns a valid 'local' tar name. Specifically translates any
   ':' symbols into '-' because the ':' is interpreted by tar to be a remote
   file which causes local untar! to fail during an import."
  [target-name]
  (s/replace target-name #":" "-"))

(defn tar!
  [tar-dir]
  (let [as-file (io/file tar-dir)
        par-dir (.getParentFile as-file)
        dirname (.getName as-file)
        out-tar-file (str dirname ".tar.gz")
        return-tar-name (str tar-dir ".tar.gz")
        {:keys [exit out err]} (sh "tar" "cvzf" out-tar-file dirname :dir par-dir)]
    (if (zero? exit)
      return-tar-name
      (throw (ex-info (str "tar execution did not run as expected: " err)
                      {:std-err err
                       :std-out out})))))

(defn untar!
  [tar-file]
  (let [as-file (io/file tar-file)
        par-dir (.getParentFile as-file)
        sname (.getName as-file)
        {:keys [exit out err]} (sh "tar" "xvf" sname :dir par-dir)]
    (when-not (zero? exit)
      (throw (ex-info (str "tar execution did not run as expected: " err)
                      {:std-err err
                       :std-out out})))))
