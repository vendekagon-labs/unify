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
(ns user
  (:require [datomic.api :as d]
            [clojure.data.csv :as csv]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.vendekagonlabs.unify.db.backend :as backend]
            [com.vendekagonlabs.unify.cli :as cli]
            [com.vendekagonlabs.unify.cli.error-handling :as err]))

(defn read-tsv [csv-file]
  (with-open [reader (io/reader csv-file)]
    (doall
     (csv/read-csv reader :separator \tab))))

(defn make-tsv
  "Dev helper fn for making CSVs, especially for query results."
  [fname cols tuples]
  (with-open [writer (io/writer fname)]
    ;; col names
    (csv/write-csv writer [cols] :separator \tab)
    ;; rest of csv
    (csv/write-csv writer tuples :separator \tab)))


(defn unify [& args]
  (with-redefs [backend/db-base-uri
                (fn [] "datomic:mem://")
                err/exit
                (fn [code msg]
                  (println "Would have exited with code" code
                           "and message:\n" msg))]
    (apply cli/-main args)))

(comment
  :unify-cli
  (def db-name "unify-test")
  (def working-dir "/Users/vendekagon-labs")
  (def import-config "/Users/vendekagon-labs/code/unify/test/resources/reference-import/template-dataset/config.edn")
  (def schema-dir "/Users/vendekagon-labs/code/unify/test/resources/reference-import/template-dataset/schema")

  (unify "delete-db" "--database" db-name)
  (unify "request-db" "--database" db-name "--schema-directory" schema-dir)
  (unify "prepare" "--import-config" import-config
                  "--working-directory" working-dir)
  (unify "transact" "--working-directory" working-dir
                   "--database" db-name))

(comment
  :query
  (def db-uri "datomic:mem://unify-test")
  (def conn (d/connect db-uri))
  (def db (d/db conn))

  (def gene-names
    (d/q '[:find ?sym (pull ?g [:gene/ensembl-id
                                :gene/hgnc-prev-symbols
                                :gene/alias-hgnc-symbols])
           :in $
           :where
           [?g :gene/hgnc-symbol ?sym]]
         db))

  ;; first we make a table containing all alias info, embedded in the table
  ;; as a json string.
  (def gene-table
    (map (fn [[gene-sym alias-map]]
           [gene-sym (json/json-str alias-map)])
         gene-names))

  (make-csv "gene_names.csv" ["hgnc_symbol" "other_names"] gene-table)

  (def ensembl->hgnc
    (d/q '[:find ?ensembl-id ?hgnc-sym
           :in $
           :where
           [?g :gene/hgnc-symbol ?hgnc-sym]
           [?g :gene/ensembl-id ?ensembl-id]]
         db))
  (take 5 ensembl->hgnc)

  (make-csv "ensembl2hgnc.csv" ["ensembl_id" "hgnc_symbol"] ensembl->hgnc)


  (d/q '[:find (count ?m)
         :in $
         :where
         [?m :measurement/fpkm]]
       db))

(comment
  ;; Queries for answering questions about reference data presence/absence in
  ;; the CANDEL reference schema (v1.3.1)
  :construct-gene-lookup
  (def db-name "unify-test")
  (def schema-dir "/Users/vendekagon-labs/code/unify/test/resources/reference-import/template-dataset/schema")
  (unify "request-db" "--database" db-name "--schema-directory" schema-dir)

  (def db-uri (str "datomic:mem://" db-name))
  (def conn (d/connect db-uri))
  (def db (d/db conn))

  (def problem-gene "FAM183A")
  (def genes
    (d/q '[:find ?hgnc ?prev ?alias
           :in $
           :where
           [?g :gene/hgnc-symbol ?hgnc]
           [?g :gene/previous-hgnc-symbols ?prev]
           [?g :gene/alias-hgnc-symbols ?alias]]
         db))

  (def all-genes
    (set (map first (d/q '[:find ?hgnc
                           :in $
                           :where
                           [?g :gene/hgnc-symbol ?hgnc]]
                         db))))


  (all-genes "AGFR2")

  (defn gene-remap-lookup
    "Given an ordered coll of [curr-hgnc-symbol, prev-hgnc-symbol, alias-hgnc-symbol]
    eg in form as returned by Datomic query, returns a lookup that will resolve a gene
    identified by previous hgnc symbol or alias hgnc symbol to its current, canonical
    hgnc symbol."
    [gene-list]
    (let [by-fn (fn [genes pos-fn]
                  (into {} (for [[hgnc row] (group-by pos-fn genes)]
                             [hgnc (ffirst row)])))
          by-prev (by-fn genes second)
          by-alias (by-fn genes #(nth % 2))]
      (merge by-prev by-alias)))

  (def lookup
    (gene-remap-lookup genes))

  (d/q '[:find ?new-hgnc
         :in $ ?old-hgnc
         :where
         [?g :gene/previous-hgnc-symbols ?old-hgnc]
         [?g :gene/hgnc-symbol ?new-hgnc]]
       db problem-gene))


(comment
  :dataset-fixing

  (def fpath
    "/Users/vendekagon-labs/code/unify/test/resources/reference-import/template-dataset/processed/cnv_ref_fixed_1.tsv")

  (def fixed-fpath
    "/Users/vendekagon-labs/code/unify/test/resources/reference-import/template-dataset/processed/cnv_ref_fixed_again_1.tsv")


  (def fpath-3
    "/Users/vendekagon-labs/code/unify/test/resources/reference-import/template-dataset/processed/cnv_ref_fixed_3.csv")

  (def fixed-fpath-3
    "/Users/vendekagon-labs/code/unify/test/resources/reference-import/template-dataset/processed/cnv_ref_fixed_again_3.tsv")
  (def ds (read-tsv fpath))
  (map count ds)
  (first ds)
  (second ds)

  ;; third position (nth 2) is a card many row, delimited by ;, need to split,
  ;; remap names not in gene list, and join back together.
  (defn remap-names [lookup names-as-str]
    (let [gene-names (str/split names-as-str #";")
          replaced (keep (fn [gene-name]
                           (if (all-genes gene-name)
                             gene-name
                             (when-let [new-name (get lookup gene-name)]
                               new-name)))
                         gene-names)]
      (apply str (interpose ";" replaced))))

  (defn fix [ds]
    (mapv (fn [[col1 col2 gene-col]]
            [col1 col2 (remap-names lookup gene-col)])
          (rest ds)))

  (def fixed-ds (fix ds))

  (def ds-3 (read-tsv fpath-3))
  (map count ds-3)
  (def fixed-ds-3 (fix ds-3))
  (def fixed-ds (fix ds))

  (make-tsv fixed-fpath-3 (first ds-3) fixed-ds-3)
  (make-tsv fixed-fpath (first ds) fixed-ds)

  ;; fix variant ref files as well
  (def variant-ref-fpath
    "/Users/vendekagon-labs/code/unify/test/resources/reference-import/template-dataset/processed/variant_ref_21.tsv")
  (def variant-ref-fixed-fpath
    "/Users/vendekagon-labs/code/unify/test/resources/reference-import/template-dataset/processed/variant_ref_21_fixed.tsv")

  (def variant-ds (read-tsv variant-ref-fpath))
  variant-ds

  (defn gene-entry? [row-as-vec]
    (= "Hugo_Symbol" (nth row-as-vec 3)))

  (defn fix-gene-rows [gene-rows]
    (keep (fn [row]
            (let [gene-symbol (nth row 4)]
              (if (all-genes gene-symbol)
                row
                (when (get lookup gene-symbol)
                  (update row 4 lookup)))))
          gene-rows))

  (defn fix-variant-ds [variant-ds]
    (let [hdr (take 1 variant-ds)
          rows (rest variant-ds)
          gene-rows (filter gene-entry? rows)
          other-rows (remove gene-entry? rows)
          fixed-gene-rows (fix-gene-rows gene-rows)]
      (concat hdr fixed-gene-rows other-rows)))

  (def fixed-variant-ds
    (fix-variant-ds variant-ds))

  (count variant-ds)
  (count fixed-variant-ds)

  (def hdr (first variant-ds))
  (make-tsv variant-ref-fixed-fpath hdr (rest fixed-variant-ds))

  (def variant-ref-fpath-32
    "/Users/vendekagon-labs/code/unify/test/resources/reference-import/template-dataset/processed/variant_ref_32.tsv")
  (def variant-ref-fixed-fpath-32
    "/Users/vendekagon-labs/code/unify/test/resources/reference-import/template-dataset/processed/variant_ref_21_fixed")

  (def variant-ds-32
    (read-tsv variant-ref-fpath-32))
  (def fixed-variant-ds-32 (fix-variant-ds variant-ds-32))
  (make-tsv variant-ref-fixed-fpath-32 hdr fixed-variant-ds-32)

  ;; Fix variants.txt
  (def variants-fpath "test/resources/reference-import/template-dataset/processed/variants.txt")
  (def variants-ds (read-tsv variants-fpath))
  (def hdr (first variants-ds))
  (nth (second variants-ds) 5)
  (def fixed-variants
    (keep (fn [row]
            (let [gene-symbol (nth row 5)]
              (if (all-genes gene-symbol)
                row
                (when-let [resolved-hgnc (get lookup gene-symbol variants-ds)]
                  (assoc row 5 resolved-hgnc)))))
          (rest variants-ds)))
  (take 10 fixed-variants)
  (count fixed-variants)
  (first fixed-variants)
  (count fixed-variants)
  (count (rest variants-ds))
  (make-tsv variants-fpath hdr fixed-variants)

  ;; Extend fixes to matrix files.
  (def dense-matrix-path
    "/Users/vendekagon-labs/code/unify/test/resources/matrix/dense-rnaseq.tsv")
  (def sparse-matrix-path
    "/Users/vendekagon-labs/code/unify/test/resources/matrix/short-processed-counts.tsv")
  (def dense-matrix
    (read-tsv dense-matrix-path))
  (def sparse-matrix
    (read-tsv sparse-matrix-path))

  (def sparse-hdr (first sparse-matrix))
  (def sparse-data (rest sparse-matrix))

  (def sparse-data-clean
    (keep
      (fn [[barcode hugo count]]
        (if (all-genes hugo)
          [barcode hugo count]
          (when-let [new-hgnc (get lookup hugo)]
            [barcode new-hgnc count])))
      sparse-data))

  (count sparse-data)
  (count sparse-data-clean)

  (make-tsv "test/resources/matrix/short-processed-counts-fixed.tsv"
            sparse-hdr sparse-data-clean)

  (def orig-dense-hdr (first dense-matrix))
  (def dense-data (rest dense-matrix))
  (def renamed-dense-hdr*
    (map (fn [hgnc]
           (if (all-genes hgnc)
             hgnc
             (get lookup hgnc "TODO_DROP")))
         (rest orig-dense-hdr)))
  (def renamed-dense-hdr (cons (first orig-dense-hdr) renamed-dense-hdr*))

  (defn drop-genes [hdr data]
    (let [clean-hdr (remove #(= "TODO_DROP" %) hdr)
          clean-data (mapv (fn [row]
                            (let [row-as-map (zipmap renamed-dense-hdr row)
                                  wo-bad-genes (dissoc row-as-map "TODO_DROP")]
                              (mapv #(get wo-bad-genes %) clean-hdr)))
                           data)]
      (vec (concat [clean-hdr] clean-data))))

  (def fixed-dense-matrix (drop-genes renamed-dense-hdr dense-data))
  (mapv count fixed-dense-matrix)
  (count fixed-dense-matrix)

  (make-tsv "test/resources/matrix/dense-rnaseq-fixed.tsv"
            (first fixed-dense-matrix)
            (rest fixed-dense-matrix)))