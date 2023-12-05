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
(ns com.vendekagonlabs.unify.bootstrap.data.anatomic-sites
  "This data extracts anatomic site information from the NIH Genomic Data
  Commons (GDC) data model via a sample file. Data from the GDC's data model
  can be freely used with attribution, see:

  https://gdc.cancer.gov/about-gdc/gdc-policies

  The data collected in the sample.yaml file this namespace refers to is the full
  enumerated of biospecimen_antatomic_site in the GDC data model, see:

  https://docs.gdc.cancer.gov/Data_Dictionary/viewer/#?view=table-definition-view&id=sample&anchor=biospecimen_anatomic_site

  For the full listing.
  "
  (:require [yaml.core :as yaml]
            [com.vendekagonlabs.unify.util.io :as util.io]))


(defn init
  "Returns transaction data for anatomic-site entities for db initialization. opts is a map
  with the following keys
    :gdc-sample-file           The path to the file containing the sample information from the
                                Genomics Data Common dictionary (sample.yaml)"
  [gdc-sample-file]
  (->> (yaml/from-file gdc-sample-file)
       :properties
       :biospecimen_anatomic_site
       :enum
       (map #(hash-map :gdc-anatomic-site/name %))))

(defn generate-tx-data
  [{:keys [gdc-sample-file output-file]}]
  (let [anatomic-site-data (init gdc-sample-file)]
    (util.io/write-tx-data anatomic-site-data output-file)))


(comment
  (generate-tx-data
    {:gdc-sample-file "seed_data/raw/anatomic_sites/sample.yaml"
     :output-file "seed_data/edn/all-anatomic-site-tx-data.edn"}))
