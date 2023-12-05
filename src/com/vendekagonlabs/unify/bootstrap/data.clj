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
(ns com.vendekagonlabs.unify.bootstrap.data
  (:require [com.vendekagonlabs.unify.util.aws :as s3]
            [com.vendekagonlabs.unify.db.config :as db.config]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [clojure.java.io :as io]))

(def seed-data-dir "seed_data/edn/")

(defn reference-data-bucket []
  (db.config/reference-data-bucket))

(defn all-datasets
  "Return all bootstrap data that needs to be loaded into a fresh db"
  []
  ;; validation map consists of a ':query' and a ':result' -- if query returns result,
  ;; we can assume that this dataset was correctly and fully transacted to the database.
  ;; expanded: install is a fn that will install the data by transacting into a datomic conn
  ;; name: used for logging, etc., maybe other future annotation

  ;; files in the :files vector are transacted in order
  [{:name :genes
    :query '[:find (count ?g)
             :where
             [?g :gene/hgnc-symbol]]
    :expected [[42843]]
    :files ["all-coordinates-tx-data.edn"
            "all-genes-tx-data.edn"
            "all-gene-products-tx-data.edn"]}
   {:name :drugs
    :query '[:find (count ?d)
             :where
             [?d :drug/preferred-name]]
    :expected [[53715]]
    :files ["all-drug-tx-data.edn"]}
   {:name :diseases
    :query '[:find (count ?d)
             :where
             [?d :meddra-disease/preferred-name]]
    :expected [[23389]]
    :files ["all-disease-tx-data.edn"]}
   {:name :proteins-epitopes
    :query '[:find (count ?p)
             :where
             (or [?p :protein/uniprot-name]
                 [?p :epitope/id])]
    :expected [[77807]]
    :files ["all-protein-epitope-tx-data.edn"]}
   {:name :cell-types
    :query '[:find (count ?c)
             :where
             [?c :cell-type/co-name]]
    :expected [[2319]]
    :files ["all-cell-type-tx-data.edn"]}
   {:name :gdc-anatomic-sites
    :query '[:find (count ?c)
             :where
             [?c :gdc-anatomic-site/name]]
    :expected [[285]]
    :files ["all-anatomic-site-tx-data.edn"]}
   {:name :so-sequence-features
    :query '[:find (count ?c)
             :where
             [?c :so-sequence-feature/name]]
    :expected [[2482]]
    :files ["all-so-sequence-features-tx-data.edn"]}
   {:name :nanostring-signatures
    :query '[:find (count ?s)
             :where
             [?s :nanostring-signature/name]]
    :expected [[42]]
    :files ["all-nanostring-signatures-tx-data.edn"]}])

(defn open-datasets
  "Return only reference datasets that are available for use by the
  general public without a license (non-proprietary datasets)"
  []
  (let [datasets (all-datasets)]
    (filter (fn [{:keys [name]}]
              (#{:genes} name))
            datasets)
    #_(remove
        (fn [{:keys [name]}]
          (#{:nanostring-signatures :drugs :diseases} name))
        datasets)))

(defn maybe-download
  "If seed data doesn't exist locally, will download it. Returns (as java.io.File objects),
   the seed data files which will then need to be transacted"
  [schema-version dataset]
  (doall
    (for [f (:files dataset)]
      (let [out-f (io/file seed-data-dir f)
            s3-key (str schema-version "/" f)]
        (when-not (util.io/exists? out-f)
          (s3/get-file (reference-data-bucket) s3-key out-f))
        out-f))))

(comment
  (doseq [dataset (all-datasets)]
    (maybe-download dataset)))
