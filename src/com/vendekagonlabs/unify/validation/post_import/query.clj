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
(ns com.vendekagonlabs.unify.validation.post-import.query
  (:require [clojure.tools.logging :as log]
            [com.vendekagonlabs.unify.db.query :as dq]
            [com.vendekagonlabs.unify.db :as db]
            [clojure.string :as str]
            [com.vendekagonlabs.unify.db.metamodel :as metamodel]
            [com.vendekagonlabs.unify.db.schema :as db.schema]))

;; In v1 and on query based validations are now grouped by the kind of entity
;; to which they apply.
(def query-based-validations
  {:query/rules '[[(measurement-technology ?m ?t-ident)
                   [?e :measurement-set/measurements ?m]
                   [?a :assay/measurement-sets ?e]
                   [?a :assay/technology ?t]
                   [?t :db/ident ?t-ident]]]

   :measurement '[{:name    :nanostring-measurements-technology
                   :query   [:find ?t-ident
                             :in $ % ?m
                             :where
                             (or-join [?m]
                               [?m :measurement/nanostring-count]
                               [?m :measurement/nanostring-signature-score])
                             (measurement-technology ?m ?t-ident)]
                   :allowed [[:assay.technology/nanostring]]}
                  {:name    :tcr-measurements-technology
                   :query   [:find ?t-ident
                             :in $ % ?m
                             :where
                             (or-join [?m]
                               [?m :measurement/tcr-clonality]
                               [?m :measurement/tcr-frequency]
                               [?m :measurement/tcr-v]
                               [?m :measurement/tcr-d]
                               [?m :measurement/tcr-j])
                             (measurement-technology ?m ?t-ident)]
                   :allowed [[:assay.technology/TCR-seq]]}
                  {:name    :sequencing-measurements-technology
                   :query   [:find ?t-ident
                             :in $ % ?m
                             :where
                             (or-join [?m]
                               [?m :measurement/segment-mean-lrr]
                               [?m :measurement/a-allele-cn]
                               [?m :measurement/b-allele-cn]
                               [?m :measurement/baf-n]
                               [?m :measurement/baf]
                               [?m :measurement/loh]
                               [?m :measurement/average-depth]
                               [?m :measurement/fraction-aligned-reads]
                               [?m :measurement/tumor-purity]
                               [?m :measurement/contamination])
                             (measurement-technology ?m ?t-ident)]
                   :allowed [[:assay.technology/WES]
                             [:assay.technology/WGS]]}
                  {:name    :variant-measurements-technology
                   :query   [:find ?t-ident
                             :in $ % ?m
                             :where
                             (or-join [?m]
                               [?m :measurement/t-ref-count]
                               [?m :measurement/t-alt-count]
                               [?m :measurement/t-depth]
                               [?m :measurement/n-ref-count]
                               [?m :measurement/n-alt-count]
                               [?m :measurement/n-depth]
                               [?m :measurement/vaf])
                             (measurement-technology ?m ?t-ident)]
                   :allowed [[:assay.technology/WES]
                             [:assay.technology/WGS]
                             [:assay.technology/dPCR]]}
                  {:name    :single-cell-measurements-technology
                   :query   [:find ?t-ident
                             :in $ % ?m
                             :where
                             (or-join [?m]
                               [?m :measurement/median-channel-value]
                               [?m :measurement/cell-count]
                               [?m :measurement/percent-of-parent]
                               [?m :measurement/percent-of-lymphocytes]
                               [?m :measurement/percent-of-leukocytes]
                               [?m :measurement/percent-of-live]
                               [?m :measurement/percent-of-singlets]
                               [?m :measurement/singlets-count]
                               [?m :measurement/live-count]
                               [?m :measurement/lymphocyte-count]
                               [?m :measurement/leukocyte-count]
                               [?m :measurement/nuclei-count])
                             (measurement-technology ?m ?t-ident)]
                   :allowed [[:assay.technology/mass-cytometry]
                             [:assay.technology/flow-cytometry]
                             [:assay.technology/vectra]
                             [:assay.technology/IHC]
                             [:assay.technology/IMC]
                             [:assay.technology/CODEX]
                             [:assay.technology/CODEX]]}
                  {:name    :rnaseq-measurements-technology
                   :query   [:find ?t-ident
                             :in $ % ?m
                             :where
                             (or-join [?m]
                               [?m :measurement/tpm]
                               [?m :measurement/fpkm]
                               [?m :measurement/rpkm]
                               [?m :measurement/rsem-normalized-count]
                               [?m :measurement/rsem-raw-count]
                               [?m :measurement/rsem-scaled-estimate])
                             (measurement-technology ?m ?t-ident)]
                   :allowed [[:assay.technology/RNA-seq]]}
                  {:name    :atacseq-measurements-technology
                   :query   [:find ?t-ident
                             :in $ % ?m
                             :where
                             (or-join [?m]
                               [?m :measurement/fraction-reads-in-peaks]
                               [?m :measurement/tss-score]
                               [?m :measurement/chromvar-tf-binding-score])
                             (measurement-technology ?m ?t-ident)]
                   :allowed [[:assay.technology/ATAC-seq]]}]
                   ; this checks that the therapies order for each subject
                   ; form a complete monotonically increasing set
         :subject '[{:name    :subject-therapies-order
                     :query   [:find ?s (count ?t)
                               :in $ % ?s
                               :where
                               [?s :subject/therapies ?t]
                               [?t :therapy/order ?o]]
                     :allowed [:find ?s (max ?o)
                               :in $ % ?s
                               :where
                               [?s :subject/therapies ?t]
                               [?t :therapy/order ?o]]}]})

(def applies-to (set (keys query-based-validations)))


(defn validate-by-query
  "Given a validations map with query, name, and expected keys, prints to stdout error messages for
  any queries that fail validation"
  [db entity]
  (let [kind (namespace (first (keys entity)))]
    (if-not (applies-to kind)
      entity
      (let [schema (db.schema/get-metamodel-and-schema db)
            ;; get UID, construct lookup ref?
            uid-attr (metamodel/need-uid? schema kind)
            uid-val (get entity uid-attr)
            lookup-ref [uid-attr uid-val]
            ruleset (get query-based-validations :query/rules)
            validations (:validations query-based-validations)
            results (->> (keep (fn [{:keys [allowed name query] :as v}]
                                 (let [q-result (dq/q+retry query
                                                            db ruleset lookup-ref)
                                       allowed-value (if (= :find (first allowed))
                                                       (dq/q+retry allowed
                                                                   db ruleset lookup-ref)
                                                       allowed)
                                       allowed-set (when allowed-value
                                                     (into #{} allowed-value))]
                                   (if (and allowed-value
                                            (not (every? allowed-set q-result)))
                                     (str "Invalid result for " (str name) "\n"
                                          "Allowed values: " allowed-set " but query returned: " q-result)
                                     (do (log/debug "Passed validation for " (str name))
                                         nil))))
                               validations)
                         (str/join "\n"))]
        (if (str/blank? results)
          entity
          (merge-with concat
                      entity
                      {:unify.validation/errors [results]}))))))