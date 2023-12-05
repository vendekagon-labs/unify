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
(ns com.vendekagonlabs.unify.util.uuid
  "Utilities for generating version 5 (SHA-1 hashing) uuids.
  This implementation should conform to RFC 4122, section 4.3.
  Includes shortcuts for generating uuids for breeze rule hashes."
  (:require [com.vendekagonlabs.unify.util.text :refer [->str]])
  (:import (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util UUID)))

(defn random
  "Generates a random UUID as a string."
  []
  (str (UUID/randomUUID)))

(defn string->md5-hash
  [^String s]
  (let [hashing-algorithm (MessageDigest/getInstance "MD5")
        raw (.digest hashing-algorithm (.getBytes s))]
    (format "%032x" (BigInteger. 1 raw))))

(defn random-partial
  ([]
   (random-partial 8))
  ([len]
   (.substring (str (UUID/randomUUID)) 0 len)))

;; through some trivial sleuthing it was determined that the snippet
;; below was previously copied from: https://gist.github.com/favila/9044218
(defn message-digest-sha1
  "Generates a message digest SHA-1 instance."
  []
  (MessageDigest/getInstance "SHA-1"))

(defn putUUID [^ByteBuffer bb ^UUID uuid]
  (doto bb
    (.putLong (.getMostSignificantBits uuid))
    (.putLong (.getLeastSignificantBits uuid))))

(defprotocol Bytes
  (as-bytes [o] o))

(extend-protocol Bytes
  (Class/forName "[B") ;; primitive byte array
  (as-bytes [o] o)

  java.nio.ByteBuffer
  (as-bytes [o] (.array o))

  java.util.UUID
  (as-bytes [o] (-> (ByteBuffer/wrap (byte-array 16))
                    (putUUID o)
                    (.array)))

  java.lang.String
  (as-bytes [o] (.getBytes o StandardCharsets/UTF_8)))

(defn sha1-bytes
  [namespace-uuid name]
  (let [md (message-digest-sha1)]
    (.update md (as-bytes namespace-uuid))
    (.digest md (as-bytes name))))

(defn sha1->v5uuid
  "Return a version 5 (name-based) uuid from sha1 hash byte-array.
  The sha1 hash should already have the namespace uuid and the name bytes
  hashed into it. The uuid will use 122 bits of entropy from the sha1 hash."
  [^bytes sha1]
  (let [bb (ByteBuffer/wrap sha1 0 16)
        ;; set bits 12-15 to 5 (version number for sha1 name-based uuid)
        msb (-> (.getLong bb)
                (bit-and-not 0xa000)
                (bit-or 0x5000))
        ;; set highest 2 bits to 0b10 (variant bits for RFC 4122 UUID)
        lsb (-> (.getLong bb)
                (bit-and-not 0x4000000000000000)
                (bit-or Long/MIN_VALUE))]
    (UUID. msb lsb)))

(defn sha1-name-uuid
  "Return a sha1 name-based uuid (version 5) from a namespace uuid and a name.
  Either argument can be anything coercible to byte-arrays"
  [namespace-uuid name]
  (sha1->v5uuid (sha1-bytes namespace-uuid name)))

(defn v5
  "Generates a v5 UUID given a namespace and name."
  [namespace name]
  (str (sha1-name-uuid (->str namespace) (->str name))))
