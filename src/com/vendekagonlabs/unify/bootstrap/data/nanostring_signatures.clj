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
(ns com.vendekagonlabs.unify.bootstrap.data.nanostring-signatures
  "This namespace contains utilities for processing nanostring signatures into
  CANDEL schema compatible transactions. The files referred to are proprietary
  and cannot be redistributed."
  (:require [clojure.data.csv :as csv]
            [com.vendekagonlabs.unify.util.collection :as collection]
            [clojure.java.io :as io]
            [com.vendekagonlabs.unify.util.io :as util.io]))


(defn init
  [{:keys [nanostring-signatures-file
           all-hugos-file]}]
  (with-open [reader (io/reader nanostring-signatures-file)]
    (let [csv-data (->> (csv/read-csv reader :separator \tab)
                        (collection/csv-data->maps))
          all-hugo (util.io/read-edn-file all-hugos-file)]
      (->> csv-data
           (keep
             (fn [ns-row]
               (let [sig (str "IO360/" (get ns-row "signature_name"))
                     g (get ns-row "gene")
                     w (get ns-row "weight")]
                 (when (all-hugo g)
                   {:nanostring-signature/name sig
                    :nanostring-signature/gene-weights
                    [[[:gene/hgnc-symbol g]
                      (Float/parseFloat w)]]}))))
           (group-by :nanostring-signature/name)
           (vals)
           (mapv
             (fn [sig-block]
               (let [sig-name (get-in sig-block [0 :nanostring-signature/name])
                     gene-weights (map :nanostring-signature/gene-weights sig-block)]
                 {:nanostring-signature/name sig-name
                  :nanostring-signature/gene-weights
                  (vec (reduce concat gene-weights))})))))))

(defn generate-tx-data
  [{:keys [nanostring-signatures-file all-hugos-file output-file]}]
  (let [all-signatures-data (init {:nanostring-signature-file nanostring-signatures-file
                                   :all-hugos-file all-hugos-file})]
    (util.io/write-tx-data all-signatures-data output-file)))


(comment
  (generate-tx-data
    {:nanostring-signatures-file "seed_data/raw/nanostring_signatures/PICISignatureAlgorithms20190214_for_candel_schema.txt"
     :all-hugos-file "seed_data/edn/all-hugos.edn"
     :output-file "seed_data/edn/all-nanostring-signatures-tx-data.edn"}))
