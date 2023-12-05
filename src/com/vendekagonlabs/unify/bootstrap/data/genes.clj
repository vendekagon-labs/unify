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
(ns com.vendekagonlabs.unify.bootstrap.data.genes
  "This namespace contains utilities for extracting HGNC (Hugo nomenclature) for genes
  into CANDEL's schema. HGNC data is distributed free of restriction. For more information,
  see:

  https://www.genenames.org/download/statistics-and-files/

  Where files can also be downloaded."
  (:require [clojure.data.csv :as csv]
            [com.vendekagonlabs.unify.util.collection :as collection]
            [clojure.java.io :as io]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [clojure.string :as str]))


(def locus-group-mapping
  {"protein-coding gene" :gene.hgnc-locus-group/protein-coding-gene
   "non-coding RNA"      :gene.hgnc-locus-group/non-coding-rna
   "pseudogene"          :gene.hgnc-locus-group/pseudogene
   "withdrawn"           :gene.hgnc-locus-group/withdrawn
   "other"               :gene.hgnc-locus-group/other
   "phenotype"           :gene.hgnc-locus-group/phenotype})

(defn try-parse-date
  [x]
  (if (not= x "")
    (.parse
      (java.text.SimpleDateFormat. "yyyy-mm-dd") x)
    x))

(defn try-split-field
  [x]
  (if (not= x "")
    (-> x
        (str/replace #"\"" "")
        (str/split #"\|"))
    x))


(defn- parse-hgnc-row
  [x]
  {:gene/hgnc-symbol              (x "symbol")
   :gene/hgnc-id                  (x "hgnc_id")
   :gene/hgnc-name                (x "name")
   :gene/alias-hgnc-symbols       (try-split-field (x "alias_symbol"))
   :gene/alias-hgnc-names         (try-split-field (x "alias_name"))
   :gene/previous-hgnc-symbols    (try-split-field (x "prev_symbol"))
   :gene/previous-hgnc-names      (try-split-field (x "prev_name"))
   :gene/refseq-accession         (x "refseq_accession")
   :gene/ensembl-id               (x "ensembl_gene_id")
   :gene/hgnc-locus-group         (locus-group-mapping (x "locus_group"))
   :gene/date-hgnc-symbol-changed (try-parse-date (x "date_symbol_changed"))
   :gene/date-hgnc-name-changed   (try-parse-date (x "date_name_changed"))})

(defn- generate-gene-product
  [x]
  (let [s (:gene/hgnc-symbol x)]
    {:gene-product/id s
     :gene-product/gene [:gene/hgnc-symbol s]}))

(defn- remove-empty-fields
  [m]
  (->> m
       (remove #(= "" (% 1)))
       (into {})))

(defn- add-coordinates
  [coords x]
  (if-let [c (coords (x :gene/hgnc-id))]
    (assoc x :gene/genomic-coordinates [[:genomic-coordinate/id  (c :genomic-coordinate/id)]])
    x))


(defn- load-hgnc
  [file coordinates]
  (with-open [reader (io/reader file)]
    (let [xf (comp
               (map parse-hgnc-row)
               (map remove-empty-fields)
               (remove #(= (:gene/hgnc-locus-group %) :gene.hgnc-locus-group/withdrawn))
               (map (partial add-coordinates coordinates)))]
      (->> (csv/read-csv reader :separator \tab)
           (collection/csv-data->maps)
           (into [] xf)))))


(defn- parse-coordinate-row
  [assembly x]
  (let [start (x "start")
        end (x "stop")
        strand "+"
        contig (str "chr" (x "seq_region"))
        id (str/join ":" [(name assembly) contig strand start end])]
    (if (not= start "")
      [(str "HGNC:" (x "hgnc_id"))
       {:genomic-coordinate/start (Integer/parseInt start)
        :genomic-coordinate/end (Integer/parseInt end)
        :genomic-coordinate/strand strand
        :genomic-coordinate/contig contig
        :genomic-coordinate/assembly assembly
        :genomic-coordinate/id id}]
      nil)))



(defn load-coordinates
  [file assembly]
  (with-open [reader (io/reader file)]
    (->> (csv/read-csv reader)
         (collection/csv-data->maps)
         (keep (partial parse-coordinate-row assembly))
         (into {}))))



(defn init
  "Returns transaction data for gene entities for db initialization. opts is a map
    with the following keys
      :hgnc-file           The path to the file containing the HGNC information (hgnc_complete_set.txt)
      :hgnc2ensembl-file   The path to the file containing the mapping between HGNC symbols
                            and ENSEMBL coordinates (retrievable from
                            ftp://ftp.ebi.ac.uk/pub/databases/genenames/hgnc2ensembl_coords.csv.gz)
      :assembly             The genome assembly. Refer to enums in the schema

   The return value is a map with the following keys (the data needs to be transacted in this order)
      :coords               The genomic-coordinates data
      :hgnc                 The gene data
      :gene-products        The gene-products data"

  [{:keys [hgnc-file hgnc2ensembl-file assembly]}]
  (let [coords (load-coordinates hgnc2ensembl-file assembly)
        hgnc (load-hgnc hgnc-file coords)
        gene-products (map generate-gene-product hgnc)]
    {:coords (vals coords)
     :hgnc hgnc
     :gene-products gene-products}))

(defn generate-tx-data
  [{:keys [_hgnc-file _hgnc2ensembl-file assembly] :as opts}]
  (let [gc-assembly (or assembly :genomic-coordinate.assembly/GRCh38)
        hugo-file-path "seed_data/edn/all-hugos.edn"
        all-genes-data (init opts)]
    (util.io/write-tx-data (:coords all-genes-data) "seed_data/edn/all-coordinates-tx-data.edn")
    (util.io/write-tx-data (:hgnc all-genes-data) "seed_data/edn/all-genes-tx-data.edn")
    (util.io/write-tx-data (:gene-products all-genes-data) "seed_data/edn/all-gene-products-tx-data.edn")
    (->> (keep :gene/hgnc-symbol (:hgnc all-genes-data))
         (into #{})
         (spit hugo-file-path))))


(comment
  (generate-tx-data
    {:hgnc-file          "seed_data/raw/genes/hgnc_complete_set.txt"
     :hgnc2ensembl-file  "seed_data/raw/genes/hgnc2ensembl_coords.csv"
     :assembly           :genomic-coordinate.assembly/GRCh38}))
