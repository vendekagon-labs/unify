(ns dataset.process
  "Dev namespace that allows matching/filter to datomic returned
  sets without need for third party lib writers (eg as when using
  R or pandas)."
  (:require [tech.v3.dataset :as ds]
            [datomic.api :as d]
            [clojure.string :as string]))

(def fpath
  "/Users/bkamphaus/data/variants.final.annotated.tsv")

(def variants
  (ds/->dataset
    fpath
    {:column-whitelist ["SYMBOL" "HGVSp"]}))

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
