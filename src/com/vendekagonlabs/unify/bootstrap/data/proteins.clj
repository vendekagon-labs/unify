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
(ns com.vendekagonlabs.unify.bootstrap.data.proteins
  "This namespace contains utilities for extracting UniProt information to provide
  as CANDEL protein reference data. The UniProt data is licensed CC v4.0 which allows
  commercial use. See:

  https://www.uniprot.org/help/downloads

  For more information and to obtain files."
  (:require [clojure.java.io :as io]
            [clojure.data.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip.xml :refer [xml-> xml1-> text attr= attr seq-test]]
            [clojure.string :as string]
            [com.vendekagonlabs.unify.util.io :as uio]
            [com.vendekagonlabs.unify.util.io :as util.io]))

(defn- load-gene-mappings
  "Load mappings between Uniprot and HUGO from file"
  [file]
  (with-open [reader (io/reader file)]
    (let [lines (line-seq reader)
          xf (comp
               (map #(string/split % #"\t"))
               (filter #(= (% 1) "Gene_Name"))
               (map #(vector (% 0) (% 2))))]
      (into {} xf lines))))

(defn- human?
  "Checks whether the XML entity e represents a human protein"
  [e]
  (= "Homo sapiens" (xml1-> e
                            :organism
                            :name
                            (attr= :type "scientific")
                            text)))

(def extractors
  [[:protein/preferred-name (fn [e] (str (xml1-> e
                                                 :protein
                                                 :recommendedName
                                                 :fullName
                                                 text)))]

   [:protein/cd-antigen (fn [e] (str (xml1-> e
                                             :protein
                                             :cdAntigenName
                                             text)))]
   [:protein/synonyms (fn [e]
                        (->> [(xml-> e
                                     :protein
                                     :recommendedName
                                     :shortName
                                     text)
                              (xml-> e
                                     :protein
                                     :alternativeName
                                     :shortName
                                     text)
                              (xml-> e
                                     :protein
                                     :alternativeName
                                     :fullName
                                     text)]
                             (flatten)
                             (into [])))]

   [:protein/uniprot-accessions (fn [e] (vec (xml-> e
                                                    :accession
                                                    text)))]
   [:protein/uniprot-name (fn [e] (-> (str (xml1-> e
                                                   :name
                                                   text))
                                      (string/replace #"_HUMAN" "")))]])



(defn- get-ptm-code
  "Gets the PTM code for string s"
  [s]
  (let [ptm-codes {"Phosphotyrosine" "pY"
                   "Phosphoserine" "pS"
                   "Phosphothreonine" "pT"}
        ptm (first
              (filter #(re-find (re-pattern %) s) (keys ptm-codes)))]
    (if ptm
      (ptm-codes ptm)
      ptm)))



(defn- get-ptms
  "Get all the PTMs for the XML entity e"
  [e]
  (let [pos (into [] (xml-> e
                            :feature
                            (attr= :type "modified residue")
                            :location
                            :position
                            (attr :position)))

        ptms (->> (xml-> e
                         :feature
                         (attr= :type "modified residue")
                         (attr :description))
                  (map get-ptm-code)
                  (into []))
        ret (map vector ptms pos)]
    (->> ret
         (remove #(nil? (first %)))
         (map string/join)
         (into []))))

(defn- get-isoforms
  "Get all the named isoforms for the XML entity e"
  [e]
  (into [] (xml-> e
                  :comment
                  (attr= :type "alternative products")
                  :isoform
                  :name
                  (seq-test [#(attr % :evidence)])
                  text)))

(defn- generate-epitopes
  "Given a protein entity and a list of epitopes id string, generates
  the corresponding epitope entities"
  [prot epitopes]
  (let [uniprot (:protein/uniprot-name prot)
        v (map #(hash-map
                  :epitope/protein uniprot
                  :epitope/id (str uniprot "/" %)) epitopes)]
    (into [{:epitope/id uniprot
            :epitope/protein uniprot}] v)))

(defn- parse-entity
  "Parse XML entity e, using mappings between Uniprot and HUGO as provided by gene-mappings"
  [e gene-mappings]
  (let [ret (as-> (map #(vector (% 0) ((% 1) e)) extractors) x
                  (filter #(seq (% 1)) x)
                  (into {} x)
                  (assoc x :db/id (:protein/uniprot-name x))
                  (if-let [hugo (gene-mappings ((:protein/uniprot-accessions x) 0))]
                    (assoc x :protein/gene [:gene/hgnc-symbol hugo])
                    x))
        epitopes (distinct (into (get-isoforms e) (get-ptms e)))]
    (into [ret] (generate-epitopes ret epitopes))))


(defn- ->tx-data
  "Returns transaction data (as a lazy seq) for protein and epitope entities for
  db initialization. Each element of the sequence is a vector containing the protein entity with
  all its associated epitope entities opts is a map with the following keys

    :uniprot-xml-file   The path to the UniProt data in XML format (uniprot_sprot.xml)
    :gene-mappings-file The path to the file containing mappings between UniProt
                        and orher databases for the Human proteome (HUMAN_9606_idmapping.dat)"

  [xml-reader opts]
  (let [gene-mappings (load-gene-mappings (:gene-mappings-file opts))
        xf  (comp
              (map zip/xml-zip)
              (filter human?)
              (map #(parse-entity % gene-mappings)))]
    (sequence xf (:content (xml/parse xml-reader :namespace-aware false)))))

(defn- filter-prot-data
  "Filter out any entries in 'all-data' that contain a :protein/gene [:gene/hgnc-symbol 'X'] lookup ref
  for which the gene X is not in the :hugo-file specified in the opts map."
  [opts all-data]
  (let [hugo-set (uio/read-edn-file (:hugo-file opts))
        filter-fn (fn [m]
                    (let [protein-entity (first m)] ; grab the protein entity
                      (or (nil? (:protein/gene protein-entity))
                          (hugo-set (second (:protein/gene protein-entity))))))]
    (filter filter-fn all-data)))

(defn emit-protein-epitope-data-file
  "Process the protein and epitope reference data as in ->tx-data,
  emitting it to 'target-file'
  opts map contains keys as indicated for '->tx-data' as well as:
    :hugo-file  The path to an edn file containing a collection of all legal HUGO names for filtering
                 This file can be generated with the `emit-hugos` function in the bootstrap.genes namespace"
  [opts target-file]
  (with-open [xml-reader (io/reader (:uniprot-xml-file opts))]
    (let [all-protein-data (->tx-data xml-reader opts)
          filtered-protein-data (filter-prot-data opts all-protein-data)]
      (util.io/write-tx-data filtered-protein-data target-file))))

(defn generate-tx-data
  [{:keys [gene-mappings-file uniprot-xml-file hugo-file output-file] :as opts}]
  (emit-protein-epitope-data-file opts output-file))

(comment
  (generate-tx-data
    {:gene-mappings-file "seed_data/raw/proteins-epitopes/HUMAN_9606_idmapping.dat"
     :uniprot-xml-file "seed_data/raw/proteins-epitopes/uniprot_sprot.xml"
     :hugo-file "seed_data/edn/all-hugos.edn"
     :output-file "seed_data/edn/all-protein-epitope-tx-data.edn"}))
