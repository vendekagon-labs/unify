(ns dataset.process
  "Dev namespace that allows matching/filter to datomic returned
  sets without need for third party lib writers (eg as when using
  R or pandas)."
  (:require [tech.v3.dataset :as ds]
            [datomic.api :as d]
            [clojure.string :as string]))

(def fpath
  "/Users/vendekagon-labs/code/unify/test/resources/reference-import/template-dataset/processed/cnv_ref_fixed_1.tsv ")

(def variants
  (ds/->dataset
    fpath
    #_{:column-whitelist ["SYMBOL" "HGVSp"]}))

(comment
  (ds/head variants)
  (ds/column-names variants))

(def fixed
  (ds/row-map
    variants
    (fn [row]
      {"HGVSp" (when-let [s (row "HGVSp")]
                 (-> s
                     (string/split #"p\.")
                     (second)))})))

(ds/write! fixed "test.tsv")

