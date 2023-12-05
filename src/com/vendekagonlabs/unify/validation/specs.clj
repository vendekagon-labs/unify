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
(ns com.vendekagonlabs.unify.validation.specs
  (:require [clojure.spec-alpha2 :as s]
            [clojure.set :as set]
            [clojure.string :as string]))


;; individual attribute specs
(s/def ::non-negative #(or (pos? %) (zero? %)))
(s/def ::zero-to-one #(and (>= % 0) (<= % 1)))
(s/def ::cn-range #(>= 50 % -2))

;; amino acid seq specs
(def aa-core-codes
  #{\A \R \N \D \C
    \E \Q \G \H \I
    \L \K \M \F \P
    \S \T \W \Y \V})

(def aa-indeterminate-codes
  #{\B \Z \X})

(def aa-codes (set/union aa-core-codes aa-indeterminate-codes))

(s/def ::amino-seq
  (s/and string?
         #(every? aa-codes %)))

(s/def :variant/ref-amino-acid ::amino-seq)
(s/def :variant/alt-amin-acid ::amino-seq)

(s/def :dataset/name #(<= (count %) 256))
(s/def :measurement/cell-count ::non-negative)
(s/def :measurement/tpm ::non-negative)
(s/def :measurement/fpkm ::non-negative)
(s/def :measurement/rpkm ::non-negative)
(s/def :measurement/rsem-raw-count ::non-negative)
(s/def :measurement/rsem-scaled-estimate ::non-negative)
(s/def :measurement/rsem-normalized-count ::non-negative)
(s/def :measurement/nanostring-count ::non-negative)
(s/def :measurement/vaf ::zero-to-one)
(s/def :measurement/percent-of-total-cells ::zero-to-one)
(s/def :measurement/percent-of-parent ::zero-to-one)
(s/def :measurement/percent-of-nuclei ::zero-to-one)
(s/def :measurement/percent-of-lymphocytes ::zero-to-one)
(s/def :measurement/percent-of-leukocytes ::zero-to-one)
(s/def :measurement/percent-of-live ::zero-to-one)
(s/def :measurement/live-percent ::zero-to-one)
(s/def :measurement/percent-of-singlets ::zero-to-one)
(s/def :measurement/tcr-frequency ::zero-to-one)
(s/def :measurement/tcr-clonality ::zero-to-one)
(s/def :measurement/tcr-alpha-clonality ::zero-to-one)
(s/def :measurement/tcr-beta-clonality ::zero-to-one)
(s/def :measurement/cnv-call
  #{:measurement.cnv-call/amplified
    :measurement.cnv-call/reduced
    :measurement.cnv-call/neutral})
(s/def :measurement/msi-status
  #{:measurement.msi-status/msi-h :measurement.msi-status/msi-l :measurement.msi-status/stable})

(s/def :measurement/a-allele-cn ::cn-range)
(s/def :measurement/b-allele-cn ::cn-range)
(s/def :measurement/baf-n ::zero-to-one)
(s/def :measurement/baf ::zero-to-one)

(s/def :measurement/t-alt-count ::non-negative)
(s/def :measurement/t-ref-count ::non-negative)
(s/def :measurement/t-depth ::non-negative)
(s/def :measurement/n-alt-count ::non-negative)
(s/def :measurement/n-ref-count ::non-negative)
(s/def :measurement/n-depth ::non-negative)
(s/def :measurement/cell-count ::non-negative)
(s/def :measurement/singlets-count ::non-negative)
(s/def :measurement/live-count ::non-negative)
(s/def :measurement/lymphocyte-count ::non-negative)
(s/def :measurement/leukocyte-count ::non-negative)
(s/def :measurement/nuclei-count ::non-negative)
(s/def :measurement/average-depth ::non-negative)
(s/def :measurement/fraction-aligned-reads ::zero-to-one)
(s/def :measurement/read-count ::non-negative)
(s/def :measurement/read-count-otu ::non-negative)
(s/def :measurement/read-count-otu-rarefied ::non-negative)
(s/def :measurement/tumor-purity ::zero-to-one)
(s/def :measurement/contamination ::zero-to-one)
(s/def :measurement/tss-score ::non-negative)
(s/def :measurement/fraction-reads-in-peaks ::zero-to-one)
(s/def :measurement/cfdna-ng-mL ::non-negative)
(s/def :measurement/pg-mL ::non-negative)
(s/def :measurement/ng-mL ::non-negative)
(s/def :measurement/U-L ::non-negative)
(s/def :measurement/nanostring-signature-score ::non-negative)
(s/def :measurement/chromvar-tf-binding-score ::non-negative) ; double check this
(s/def :measurement/tmb-indel ::non-negative)
(s/def :measurement/tmb-snv ::non-negative)
(s/def :measurement/tmb-total ::non-negative)
(s/def :measurement/absolute-cn ::non-negative)
(s/def :measurement/total-reads ::non-negative)
(s/def :measurement/read-count ::non-negative)
(s/def :measurement/tcr-count ::non-negative)
(s/def :measurement/tcr-alpha-clonality ::zero-to-one)
(s/def :measurement/tcr-beta-clonality ::zero-to-one)

(s/def :clinical-observation/pfs ::non-negative)
(s/def :clinical-observation/dfi ::non-negative)
(s/def :clinical-observation/os ::non-negative)
(s/def :clinical-observation/ttf ::non-negative)
(s/def :clinical-observation/bmi ::non-negative)
(s/def :clinical-observation/ldh ::non-negative)
(s/def :clinical-observation/absolute-leukocyte-count ::non-negative)
(s/def :clinical-observation/absolute-monocyte-count ::non-negative)

(s/def :sample/purity ::zero-to-one)
(s/def :timepoint/relative-order pos?)
(s/def :timepoint/offset int?)

(s/def :therapy/line pos?)
(s/def :therapy/order pos?)

(s/def :atac-peak/-log10pvalue ::non-negative)
(s/def :atac-peak/-log10qvalue ::non-negative)
(s/def :atac-peak/quality-score ::non-negative)
(s/def :atac-peak/summit-offset ::non-negative)

(s/def :subject/age ::non-negative)




;; TODO: Ben K, 2/16/2021 -- have parked HLA validation past string for the moment
;;       until we decide which format specification to use. (code length, etc)
#_(def hla-a-re
    #"A\*[0-9][0-9]:[0-9][0-9]")
(s/def :subject/HLA-A-type
  (s/coll-of string?))

;; Numeric attributes
(defn- to-string
  [x]
  (when-not (nil? x)
    (if (number? x)
      (str x)
      (name x))))

(defn- get-map-values
  [m k]
  (if (seqable? k)
    (get-in m k)
    (get m k)))

(defn str-composite-of
  "Returns a spec that validates maps by verifying that the value associated with
  the key k is the same string that is obtained by joining the values associated
  to the keys ks using the delimiter `sep`. Individual keys can be either scalar values,
  or collections, in which case the corresponding value will be retrieved using get-in.
  Namespaces are ignored for the purpose of stringification"
  [ks sep k]
  (fn [m]
    (try (= (string/join sep (into []
                                   (comp
                                     (map to-string)
                                     (remove nil? )) (map (partial get-map-values m) ks)))
            (k m))
      (catch Exception e
        (prn m)
        (throw e)))))

(def namespace-name (name (ns-name *ns*)))

(defmacro only-keys
  "Similar to spec/keys, but the required and optional keys are the only keys that are
   allowed in the map"
  [& {:keys [req req-un opt opt-un] :as args}]
  `(s/merge (s/keys ~@(apply concat (vec args)))
            (s/map-of ~(set (concat req
                                    (map (comp keyword name) req-un)
                                    opt
                                    (map (comp keyword name) opt-un)))
                      any?)))

(s/def :unify/sample (s/schema [:sample/id
                                :sample/uid
                                :sample/subject]))
(s/def ::sample (s/select :unify/sample [*]))

(s/def :unify/dataset (s/schema [:dataset/name
                                 :dataset/samples
                                 :dataset/assays
                                 :dataset/timepoints
                                 :dataset/subjects]))
(s/def ::dataset (s/select :unify/dataset [*]))

(s/def :unify/assay (s/schema [:assay/uid
                               :assay/name
                               :assay/measurement-sets
                               :assay/technology]))
(s/def ::assay (s/select :unify/assay [*]))


(s/def :unify/measurement-set-measurements (s/schema [:measurement-set/uid
                                                      :measurement-set/name
                                                      :measurement-set/measurements]))
(s/def :unify/measurement-set-matrix (s/schema [:measurement-set/uid
                                                :measurement-set/name
                                                :measurement-set/measurement-matrices]))
(s/def ::measurement-set
  (s/or ::ms-measurements (s/select :unify/measurement-set-measurements [*])
        ::ms-matrices (s/select :unify/measurement-set-matrix [*])))

(s/def :unify/clinical-observation-set (s/schema [:clinical-observation-set/uid
                                                  :clinical-observation-set/name
                                                  :clinical-observation-set/clinical-observations
                                                  :clinical-observation-set/adverse-events]))
(s/def ::clinical-observation-set (s/merge
                                    (s/select :unify/clinical-observation-set [:clinical-observation-set/name
                                                                               :clinical-observation-set/uid])
                                    (s/or :clinical-observation-set/adverse-events (s/select :unify/clinical-observation-set [:clinical-observation-set/adverse-events])
                                          :clinical-observation-set/clinical-observations (s/select :unify/clinical-observation-set [:clinical-observation-set/clinical-observations]))))

(s/def :unify/clinical-observation (s/schema [:clinical-observation/id
                                              :clinical-observation/uid
                                              :clinical-observation/subject
                                              :clinical-observation/timepoint]))
(s/def ::clinical-observation (s/select :unify/clinical-observation [*]))

(s/def :unify/adverse-event (s/schema [:adverse-event/id
                                       :adverse-event/uid
                                       :adverse-event/subject
                                       :adverse-event/meddra-adverse-event]))
(s/def ::adverse-event (s/select :unify/adverse-event [*]))

(s/def :unify/timepoint (s/schema [:timepoint/id
                                   :timepoint/uid
                                   :timepoint/type
                                   :timepoint/relative-order
                                   :timepoint/treatment-regimen]))
(s/def ::timepoint
  (s/merge (s/select :unify/timepoint [:timepoint/id
                                       :timepoint/uid
                                       :timepoint/type])
           (s/or :treatment-regimen (s/select [:timepoint/relative-order :timepoint/treatment-regimen])
                 :free-timepoint #(not (some #{:timepoint/treatment-regimen :timepoint/relative-order} (keys %))))))





;; Notes re: genomic coordinate and variant
;; ----------------
;; (1) merged from prepare, can't complete there b/c of possibility of melted data (entity fulfilled across
;;     multiple roles.
;; (2) using `and` instead of merge means no generator, BUT, b/c generator is not already
;;     defined for str-composite-of fulfilling ID gen, etc. this entity would need to be custom generated
;;     anyways.
;; (3) verbosity in HoF, explicit predicate, etc. due to macro/fn failing to fulfill spec* in spec-2 when
;;     running through spec multimethod dispatch internals, but stepping it out like this succeeds.
(s/def :unify/genomic-coordinate (s/schema [:genomic-coordinate/id
                                            :genomic-coordinate/assembly
                                            :genomic-coordinate/contig
                                            :genomic-coordinate/start
                                            :genomic-coordinate/end
                                            :genomic-coordinate/strand]))

(def gen-coord-id-matches? (str-composite-of [[:genomic-coordinate/assembly :db/ident]
                                              :genomic-coordinate/contig
                                              :genomic-coordinate/strand
                                              :genomic-coordinate/start
                                              :genomic-coordinate/end] ":" :genomic-coordinate/id))
(s/def ::matching-genomic-coordinate-id gen-coord-id-matches?)


(s/def ::genomic-coordinate
  (s/and
    (s/select :unify/genomic-coordinate [*])
    ::matching-genomic-coordinate-id))

(s/def :unify/variant (s/schema [:variant/id
                                 :variant/genomic-coordinates
                                 :variant/ref-allele
                                 :variant/alt-allele]))
(def variant-id-matches?
  (str-composite-of [[:variant/genomic-coordinates :genomic-coordinate/id]
                     :variant/ref-allele
                     :variant/alt-allele] "/" :variant/id))
(s/def ::matching-variant-id variant-id-matches?)

(s/def ::variant
  (s/and
    (s/select :unify/variant [*])
    ::matching-variant-id))

(s/def :unify/gene (s/schema [:gene/hgnc-symbol
                              :gene/hgnc-id
                              :gene/hgnc-name]))
(s/def ::gene (s/select :unify/gene [*]))

(s/def :unify/protein (s/schema [:protein/uniprot-name
                                 :protein/uniprot-accessions
                                 :protein/preferred-name]))
(s/def ::protein (s/select :unify/protein [*]))

(s/def :unify/subject (s/schema [:subject/id :subject/uid]))
(s/def ::subject (s/select :unify/subject [*]))

(s/def :unify/treatment-regimen (s/schema [:treatment-regimen/name
                                           :treatment-regimen/uid]))
(s/def ::treatment-regimen (s/select :unify/treatment-regimen [*]))


(s/def :unify/cell-population (s/schema [:cell-population/name
                                         :cell-population/cell-type
                                         :cell-population/from-clustering]))
(s/def ::cell-population
  (s/merge (s/select :unify/cell-population [:cell-population/name :cell-population/from-clustering])
           (s/or :cell-population-from-clustering #(:cell-population/from-clustering %)
                 :cell-population/from-gating (s/and #(not (:cell-population/from-clustering %))
                                                     (s/select :unify/cell-population [:cell-population/cell-type])))))

(s/def :unify/tcr (s/schema [:tcr/id
                             :tcr/alpha-1
                             :tcr/alpha-2
                             :tcr/beta]))

(s/def ::tcr
  (s/merge (s/select :unify/tcr [:tcr/id])
           (s/or :tcr-alpha (s/select :unify/tcr [:tcr/alpha-1])
                 :tcr-beta (s/select :unify/tcr [:tcr/beta]))))


(s/def :unify/therapy (s/schema [:therapy/id
                                 :therapy/uid
                                 :therapy/treatment-regimen
                                 :therapy/order]))
(s/def ::therapy (s/select :unify/therapy [*]))

(s/def :unify/cnv (s/schema [:cnv/genomic-coordinates]))
(s/def ::cnv (s/select :unify/cnv [*]))


(s/def :unify/measurement (s/schema [:measurement/id
                                     :measurement/uid
                                     :measurement/sample]))

(s/def :unify/import (s/schema [:import/name :import/user]))
(s/def ::import (s/select :unify/import [*]))

(s/def :unify/drug-regimen (s/schema [:drug-regimen/uid
                                      :drug-regimen/drug]))
(s/def ::drug-regimen (s/select :unify/drug-regimen [*]))

(s/def :unify/clinical-trial (s/schema [:clinical-trial/nct-number]))
(s/def ::clinical-trial (s/select :unify/clinical-trial [*]))

(s/def :unify/atac-peak (s/schema [:atac-peak/name
                                   :atac-peak/genomic-coordinates
                                   :atac-peak/jointly-called
                                   :atac-peak/fixed-width]))

(s/def ::atac-peak (s/select :unify/atac-peak [*]))

(s/def :unify/otu (s/schema [:otu/id
                             :otu/kingdom
                             :otu/phylum
                             :otu/class
                             :otu/order
                             :otu/family
                             :otu/genus
                             :otu/species]))

(s/def ::otu (s/select :unify/otu [:otu/id :otu/kingdom]))


(s/def :unify/meddra-disease (s/schema [:meddra-disease/preferred-name
                                        :meddra-disease/synonyms]))
(s/def ::meddra-disease (s/select :unify/meddra-disease [*]))

(s/def :unify/gdc-anatomic-site (s/schema [:gdc-anatomic-site/name]))
(s/def ::gdc-anatomic-site (s/select :unify/gdc-anatomic-site [*]))

(s/def :unify/gene-product (s/schema [:gene-product/id
                                      :gene-product/gene]))
(s/def ::gene-product (s/select :unify/gene-product [*]))

(s/def :unify/epitope (s/schema [:epitope/id
                                 :epitope/protein]))
(s/def ::epitope (s/select :unify/epitope [*]))

(s/def :unify/single-cell (s/schema [:single-cell/id]))
(s/def ::single-cell (s/select :unify/single-cell [*]))

(s/def :unify/nanostring-signature (s/schema [:nanostring-signature/name
                                              :nanostring-signature/gene-weights]))
(s/def ::nanostring-signature (s/select :unify/nanostring-signature [*]))


(s/def :unify/study-day (s/schema [:study-day/id
                                   :study-day/uid
                                   :study-day/day
                                   :study-day/reference-event]))
(s/def ::study-day (s/select :unify/study-day [*]))

;; TODO: eventually measurement matrix will need some kind of expansion to more
;; restrictive testing, a la measurement being one of certain types with
;; required targets that merge in base specs, etc. but we should wait to see
;; how this expands prior to moving in that direction.
(s/def ::measurement-matrix (only-keys
                              :req [:measurement-matrix/backing-file
                                    :measurement-matrix/name
                                    :measurement-matrix/samples
                                    :measurement-matrix/uid
                                    :measurement-matrix/measurement-type]
                              :opt [:measurement-matrix/gene-products
                                    :measurement-matrix/epitopes
                                    :measurement-matrix/single-cells
                                    :measurement-matrix/cell-populations]))


(s/def ::cell-pop-measurement (only-keys
                                :req [:measurement/id
                                      :measurement/uid
                                      :measurement/sample
                                      :measurement/cell-population]
                                :opt [:measurement/region-of-interest
                                      :measurement/epitope
                                      :measurement/percent-of-total-cells
                                      :measurement/median-channel-value
                                      :measurement/cell-count
                                      :measurement/percent-of-nuclei
                                      :measurement/percent-of-lymphocytes
                                      :measurement/percent-of-leukocytes
                                      :measurement/percent-of-live
                                      :measurement/percent-of-parent
                                      :measurement/percent-of-singlets]))

(s/def ::variant-measurement (only-keys
                               :req [:measurement/id
                                     :measurement/uid
                                     :measurement/sample
                                     :measurement/variant]
                               :opt [:measurement/t-alt-count
                                     :measurement/t-ref-count
                                     :measurement/t-depth
                                     :measurement/n-alt-count
                                     :measurement/n-ref-count
                                     :measurement/n-depth
                                     :measurement/vaf]))

(s/def ::cnv-measurement (only-keys
                           :req [:measurement/id
                                 :measurement/uid
                                 :measurement/sample
                                 :measurement/cnv]
                           :opt [:measurement/a-allele-cn
                                 :measurement/b-allele-cn
                                 :measurement/cnv-call
                                 :measurement/segment-mean-lrr
                                 :measurement/loh
                                 :measurement/baf
                                 :measurement/baf-n
                                 :measurement/absolute-cn]))


(s/def ::gene-product-measurement (only-keys
                                    :req [:measurement/id
                                          :measurement/uid
                                          :measurement/sample
                                          :measurement/gene-product]
                                    :opt [:measurement/tpm
                                          :measurement/fpkm
                                          :measurement/rpkm
                                          :measurement/rsem-raw-count
                                          :measurement/rsem-scaled-estimate
                                          :measurement/rsem-normalized-count
                                          :measurement/array-log-intensity
                                          :measurement/array-log-ratio
                                          :measurement/nanostring-count
                                          :measurement/read-count]))

(s/def ::epitope-measurement (only-keys
                               :req [:measurement/id
                                     :measurement/uid
                                     :measurement/sample
                                     :measurement/epitope]
                               :opt [:measurement/median-channel-value
                                     :measurement/luminex-mfi
                                     :measurement/protein-array-log-intensity
                                     :measurement/pg-mL
                                     :measurement/ng-mL
                                     :measurement/U-L
                                     :measurement/olink-npx
                                     :measurement/nanostring-count]))

(s/def ::tcr-measurement (only-keys
                           :req [:measurement/id
                                 :measurement/uid
                                 :measurement/sample
                                 :measurement/tcr]
                              :opt [:measurement/tcr-frequency
                                    :measurement/tcr-count
                                    :measurement/tcr-v
                                    :measurement/tcr-d
                                    :measurement/tcr-j]))

(s/def ::nanostring-signature-measurement (only-keys
                                            :req [:measurement/id
                                                  :measurement/uid
                                                  :measurement/sample
                                                  :measurement/nanostring-signature
                                                  :measurement/nanostring-signature-score]))

(s/def ::atac-peak-measurement (only-keys
                                 :req [:measurement/id
                                       :measurement/uid
                                       :measurement/sample
                                       :measurement/atac-peak
                                       :measurement/read-count]))

(s/def ::otu-measurement (only-keys
                           :req [:measurement/id
                                 :measurement/uid
                                 :measurement/sample
                                 :measurement/otu]
                           :opt [:measurement/read-count-otu
                                 :measurement/read-count-otu-rarefied]))

(s/def ::measurement-without-target (only-keys
                                      :req [:measurement/id
                                            :measurement/uid
                                            :measurement/sample]
                                      :opt [:measurement/region-of-interest
                                            :measurement/msi-status
                                            :measurement/tcr-clonality
                                            :measurement/tcr-alpha-clonality
                                            :measurement/tcr-beta-clonality
                                            :measurement/cell-count
                                            :measurement/singlets-count
                                            :measurement/live-count
                                            :measurement/lymphocyte-count
                                            :measurement/leukocyte-count
                                            :measurement/nuclei-count
                                            :measurement/live-percent
                                            :measurement/average-depth
                                            :measurement/fraction-aligned-reads
                                            :measurement/tumor-purity
                                            :measurement/contamination
                                            :measurement/tss-score
                                            :measurement/fraction-reads-in-peaks
                                            :measurement/cfdna-ng-mL
                                            :measurement/tmb-indel
                                            :measurement/tmb-snv
                                            :measurement/tmb-total
                                            :measurement/total-reads]))

(s/def ::measurement-has-value
  (s/keys :req [(or :measurement/percent-of-total-cells
                    :measurement/median-channel-value
                    :measurement/cell-count
                    :measurement/percent-of-parent
                    :measurement/percent-of-nuclei
                    :measurement/percent-of-lymphocytes
                    :measurement/percent-of-leukocytes
                    :measurement/percent-of-live
                    :measurement/percent-of-singlets
                    :measurement/live-percent
                    :measurement/t-alt-count
                    :measurement/t-ref-count
                    :measurement/t-depth
                    :measurement/msi-status
                    :measurement/n-alt-count
                    :measurement/n-ref-count
                    :measurement/n-depth
                    :measurement/vaf
                    :measurement/a-allele-cn
                    :measurement/b-allele-cn
                    :measurement/segment-mean-lrr
                    :measurement/loh
                    :measurement/baf
                    :measurement/baf-n
                    :measurement/tpm
                    :measurement/fpkm
                    :measurement/rpkm
                    :measurement/rsem-raw-count
                    :measurement/rsem-scaled-estimate
                    :measurement/rsem-normalized-count
                    :measurement/nanostring-count
                    :measurement/olink-npx
                    :measurement/median-channel-value
                    :measurement/luminex-mfi
                    :measurement/protein-array-log-intensity
                    :measurement/pg-mL
                    :measurement/ng-mL
                    :measurement/U-L
                    :measurement/tcr-beta-clonality
                    :measurement/tcr-alpha-clonality
                    :measurement/tcr-clonality
                    :measurement/tcr-frequency
                    :measurement/tcr-count
                    :measurement/cell-count
                    :measurement/singlets-count
                    :measurement/live-count
                    :measurement/lymphocyte-count
                    :measurement/leukocyte-count
                    :measurement/nuclei-count
                    :measurement/average-depth
                    :measurement/fraction-aligned-reads
                    :measurement/read-count
                    :measurement/read-count-otu
                    :measurement/read-count-otu-rarefied
                    :measurement/tumor-purity
                    :measurement/contamination
                    :measurement/tss-score
                    :measurement/fraction-reads-in-peaks
                    :measurement/chromvar-tf-binding-score
                    :measurement/nanostring-signature-score
                    :measurement/cfdna-ng-mL
                    :measurement/tmb-indel
                    :measurement/tmb-snv
                    :measurement/tmb-total
                    :measurement/absolute-cn
                    :measurement/array-log-intensity
                    :measurement/array-log-ratio
                    :measurement/total-reads
                    :measurement/read-count
                    :measurement/variant)]))

(s/def ::measurement
  (s/merge (s/select :unify/measurement [*])
           ::measurement-has-value
           (s/or :cell-pop-measurement ::cell-pop-measurement
                 :variant-measurement ::variant-measurement
                 :cnv-measurement  ::cnv-measurement
                 :gene-product-measurement ::gene-product-measurement
                 :nanostring-signature-measurement ::nanostring-signature-measurement
                 :epitope-measurement ::epitope-measurement
                 :atac-peak-measurement ::atac-peak-measurement
                 :otu-measurement ::otu-measurement
                 :tcr-measurement ::tcr-measurement
                 :measurement-without-target ::measurement-without-target)))

