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
            [clojure.java.io :as io])
  (:import (java.io InputStream)
           (java.util.zip GZIPInputStream GZIPOutputStream)))

(def ^:private gzip-buffer-size 65536)

(defn gzip-path?
  "True if path f (string or file) has a .gz extension, i.e. should be written gzipped."
  [f]
  (s/ends-with? (str f) ".gz"))

(defn gzipped-stream?
  "True if the next two bytes of `in` are the gzip magic bytes. `in` must support
  mark/reset (e.g. a BufferedInputStream); the stream position is left unchanged."
  [^InputStream in]
  (.mark in 2)
  (let [b1 (.read in)
        b2 (.read in)]
    (.reset in)
    (and (= b1 0x1f) (= b2 0x8b))))

(defn input-stream
  "Opens f (anything clojure.java.io/input-stream accepts) as an input stream,
  transparently decompressing it if its content is gzipped. Detection is by the
  gzip magic bytes, not the file extension."
  ^InputStream [f]
  (let [in (io/input-stream f)
        in (if (.markSupported ^InputStream in)
             in
             (java.io.BufferedInputStream. in))]
    (try
      (if (gzipped-stream? in)
        (GZIPInputStream. in gzip-buffer-size)
        in)
      (catch Exception e
        (.close ^InputStream in)
        (throw e)))))

(defn reader
  "Returns a (UTF-8) BufferedReader over f, transparently decompressing gzipped
  content. See `input-stream`."
  ^java.io.BufferedReader [f]
  (io/reader (input-stream f)))

(defn writer
  "Returns a (UTF-8) BufferedWriter to f, gzip-compressing output when f has a .gz
  extension. Makes parent folders if necessary."
  ^java.io.BufferedWriter [f]
  (make-parents f)
  (if (gzip-path? f)
    (io/writer (GZIPOutputStream. (io/output-stream f) gzip-buffer-size))
    (io/writer f)))

(defn slurp-file
  "Like slurp, but transparently decompresses gzipped content."
  [f]
  (with-open [rdr (reader f)]
    (slurp rdr)))

(defn spit-file
  "Like spit (without append), but gzip-compresses when f has a .gz extension."
  [f content]
  (with-open [w (writer f)]
    (.write w (str content))))

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
   ->pretty-string of data. Output is gzipped when f has a .gz extension."
  [f data]
  (spit-file f (if (string? data)
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
    (let [f-text (slurp-file f)
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
