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
(ns com.vendekagonlabs.unify.db.transact
  (:require [datomic.api :as d]
            [clojure.pprint :refer [pprint]]
            [com.vendekagonlabs.unify.db.query :as db.query]
            [clojure.edn :as edn]
            [com.vendekagonlabs.unify.db.import-coordination :as ic]
            [com.vendekagonlabs.unify.db.common :refer [retryable?]]
            [com.vendekagonlabs.unify.util.text :refer [->unifyty-string]]
            [clojure.core.async :as a]
            [com.vendekagonlabs.unify.util.uuid :as util.uuid]
            [clojure.tools.logging :as log]
            [cognitect.anomalies :as anom]
            [clojure.java.io :as io])
  (:import (java.io PushbackReader)))

(defn report-retryable
  "print errors when/if they happen - this can be improved with additional chrome"
  [f]
  (let [res (try
              @(f)
              (catch Exception e
                (let [data (ex-data e)]
                  ;; Some exceptions contain an anomaly via info
                  ;;
                  (if (and (some? data)
                           (some? (::anom/category data)))
                    data
                    {::anom/category ::anom/fault
                     ::anom/message (.getMessage e)
                     ::anom/ex-data (ex-data e)}))))]
    res))

;; retry functions all here for convenience
(defn exp-retry-fn
  [retry-n backoff]
  (* backoff (Math/pow 2 retry-n)))

(defn linear-retry-fn
  [_ backoff]
  backoff)

(defn scaled-retry-fn
  [retry-n backoff]
  (* retry-n backoff))


(defn retry
  "Retries f up to iter times or until pred if true, with msec backoff after each try.
  `skip-fn`, if it evaluates to true, will silently skip the transaction rather than retry.
  This can allow for logic that checks e.g. if the transaction made it into the database.
   `next-try-fn` applied to retry number and `backoff` returns the amount of time to wait."
  [f retry-pred skip-fn max-retries backoff next-try-fn]
  (loop [n 1]
    (when (zero? (mod n 10))
      (log/warn "Multiple retries for a single transaction, currently on attempt: " n))
    (let [result (f)]
      (if (retry-pred result)
        (do
          (log/info "retry> retrying result: " result)
          (if (<= n max-retries)
            (do
              (Thread/sleep (next-try-fn n backoff))
              (log/info "retry> attempt " n)
              (if-let [skip-result (skip-fn)]
                skip-result
                (recur (inc n))))
            (do
              (log/error "Failed to transact after " n " retries.")
              result)))
        result))))

(defn- raw-tx-fn
  [conn data]
  (d/transact-async conn data))

(defn- tx-present?
  "Returns `nil` (falsy) if tx not present in db, otherwise map with info
  on tx. Lookup is by uid on tx-data batch, also returns `nil` of tx-batch
  is not annotated with `:import/txn-id` (meaning no skip check can be made)"
  [db tx-batch]
  (when-let [tx-uid (:import/txn-id (first tx-batch))]
    (let [tx-q-res (db.query/q+retry '[:find ?tx
                                       :in $ ?txn-id
                                       :where
                                       [?tx :import/txn-id ?txn-id]]
                        db tx-uid)]
      ;; this allows e.g. anomaly check on tx result map to work
      ;; TODO: but, should it maybe try to figure out info on transaction
      ;; and report that db state, as though it were tx result?
      (when-let [tx-eid (ffirst tx-q-res)]
        {:tx-ent-id tx-eid
         :import/txn-id tx-uid}))))


(defn strip-annotations [tx-elem]
  (if (map? tx-elem)
    [(dissoc tx-elem :unify/annotations)]
    [tx-elem]))

(defn flatten-annotations [tx-elem]
  (if-not (and (map? tx-elem)
               (:unify/annotations tx-elem))
    [tx-elem]
    (let [tempid (util.uuid/random)]
      [(-> tx-elem
           (dissoc :unify/annotations)
           (assoc :db/id tempid))
       (assoc (:unify/annotations tx-elem) :unify.annotation/entity tempid)])))

(defn async-transact-w-retry
  [conn data opts]
  (let [{:keys [skip-annotations]} opts
        munge-f (if skip-annotations strip-annotations flatten-annotations)
        tx-data (mapcat munge-f data)
        skip? #(tx-present? (d/db conn) tx-data)
        res (retry (fn []
                     (report-retryable #(raw-tx-fn conn tx-data)))
                   #(retryable? %)
                   skip?
                   ;; 3 hours of retries at a pacing of 3 * # of retries
                   3600
                   3000
                   ;;linear-retry-fn
                   scaled-retry-fn)]

      (if-not (::anom/category res)
        res
        ;; if we got an error, reformat error data to squash line number annotations into one range
        (let [file (->> tx-data
                        (map :unify.annotation/filename)
                        (remove nil?)
                        first)
              line-numbers (->> tx-data
                                (map :unify.annotation/line-number)
                                (remove nil?))]
         (merge res
                (when (and file (seq line-numbers))
                  {:unify.annotation/filename file
                   :unify.annotation/start-line (apply min line-numbers)
                   :unify.annotation/end-line (apply max line-numbers)}))))))

(defn pipeline
  "Transacts data from from-ch. Returns a map with:
     :result, a return channel getting {:error t} or {:completed n}
     :stop, a fn you can use to terminate early."
  [conn conc from-ch opts]
  ;; TODO: will other options (if any added) be wired in straight to async-transact-w-tretry?
  (let [{:keys [skip-annotations transducer]} opts
        to-ch (a/chan (* 4 1024))
        done-ch (a/chan)
        transact-data (fn [data]
                        (let [result (async-transact-w-retry conn data opts)]
;;                          (println "transct result: ")
;;                          (clojure.pprint/pprint result)
                          (if (::anom/category result)
                            (do
                              (log/error "Anomaly encountered running transactions -- stopping."
                                         (->unifyty-string result))
                              (a/close! from-ch)
                              (a/close! to-ch)
                              (a/>!! done-ch result))
                            result)))]

    ; go block prints a '.' after every 10 transactions, puts completed
    ; report on done channel when no value left to be taken.
    (a/go-loop [total 0]
               (when (zero? (mod total 10))
                 (print ".") (flush))
               (if-let [c (a/<! to-ch)]
                 (recur (inc total))
                 (a/>! done-ch {:completed total})))

    ; pipeline that uses transducer form of map to transact data taken from
    ; from-ch and puts results on to-ch
    (a/pipeline-blocking conc to-ch
                         (comp
                           transducer
                           (map transact-data))
                         from-ch)

    ; returns done channel and a function that you can use
    ; for early termination.
    {:result done-ch
     :stop (fn [] (a/close! to-ch))}))


(defn diff-renames
  "Used as a transducer over tx-batches, renames all incoming data to match diff dataset name.
  Has to resolve import/name and dataset/name both in bare form, and when referred to by other
  entities."
  [{:keys [diff-suffix]} tx-batch]
  (let [append-diff-suffix (fn [import]
                             (str import "-" diff-suffix))
        tx-tx (first tx-batch)
        flat-txes (rest tx-batch)]
    (cons (-> tx-tx
              (update-in [:import/txn-id] append-diff-suffix)
              (#(if (get-in % [:import/import :import/name])
                  (update-in % [:import/import :import/name] append-diff-suffix)
                  (update-in % [:import/import 1] append-diff-suffix))))
          (map
            (fn renames [tx-map]
              (->> (for [[a v] tx-map]
                     (let [attr-name (name a)]
                       (cond
                         (or (= :import/name a)
                             (= :dataset/name a))
                         [a (append-diff-suffix v)]

                         (= attr-name "uid")
                         [a (let [[dataset path] v]
                              [(append-diff-suffix dataset) path])]

                         (= :unify.import/most-recent a)
                         [a [:import/name (append-diff-suffix (second v))]]

                         (and (coll? v))
                         [a (cond
                              (map? v)
                              (renames v)

                              (every? map? v)
                              (mapv renames v)

                              (and (sequential? v)
                                   (= 2 (count v))
                                   (-> v first keyword?))
                              (cond
                                (= "uid" (-> v first name))
                                (let [[lookup-attr [dataset path]] v]
                                  [lookup-attr [(append-diff-suffix dataset) path]])

                                (= :dataset/name (first v))
                                [(first v) (append-diff-suffix (second v))]

                                :else
                                v)

                              :else
                              v)]

                         :else
                         [a v])))
                   (into {})))
            flat-txes))))


(defn run-txns!
  "Use tx-pipeline to transact into Datomic with concurrency 'conc'
  Loop through edn files in f-list, which must be transaction-data files,
  putting transactions on the input channel used by tx-pipeline. If import-name
  is supplied, filters out transactions that were previously successful by
  their import supplied uuid."
  ([conn f-list conc opts]
   (let [{:keys [import-name dataset-name diff-suffix skip-annotations]} opts
         input-chan (a/chan (* 4 1024))
         tx-xform (if-not import-name
                    (map identity)
                    (let [db (d/db conn)
                          ;; this and import coordination dep need to be lifted out
                          ;; at that point, possibly good time for cleanup of nested
                          ;; coll logic (since partitions are put on channel, tx-map
                          ;; affecting transducers but be sub-collection fns)
                          txn-uuid-set (ic/successful-uuid-set db import-name {:invalidate false})
                          base-xform   (remove (fn [tx-batch]
                                                 (when-let [batch-txn-id (-> tx-batch first :import/txn-id)]
                                                   (when (txn-uuid-set batch-txn-id)
                                                     (log/debug "Skipping transaction: " batch-txn-id)
                                                     true))))
                          xform (if (and dataset-name diff-suffix)
                                  (comp base-xform (map (partial diff-renames opts)))
                                  base-xform)]
                      xform))
         ;; don't just pass `opts` through here to make it clear import-name is not intended for pipeline
         result-map (pipeline conn conc input-chan {:skip-annotations skip-annotations
                                                    :transducer tx-xform})]
     (doseq [path f-list]
       (log/info "Transacting tx-data file into Datomic: " (str path))
       (log/debug "Starting transaction pipelining of file: " (str path))
       (with-open [in (PushbackReader. (io/reader path))]
         (let [input-seq (->> (repeatedly #(edn/read {:eof ::eof} in))
                              (take-while #(not= % ::eof)))]
           (doseq [tx input-seq]
             (a/>!! input-chan tx))))
       (log/debug "Completed transaction pipelining of file: " (str path)))
     (a/close! input-chan)
     result-map))
  ([conn f-list conc]
   ;; Read all transactions without alteration (used during db bootstrapping)
   (run-txns! conn f-list conc {})))

(defn sync+retry
  "Attempt a synchronous transaction. If it fails with backpressure, retry after pausing
  for tempo-msec, up to max-retries."
  ([conn tx tempo-msec max-retries]
   (loop [retry 1]
     (let [tx-result @(d/transact conn tx)]
       (cond
         ;; if transaction succeeds, return result as normal
         (:db-after tx-result)
         tx-result

         ;; with back pressure, wait and retry
         (and (retryable? tx-result)
              (<= retry max-retries))
         (do (log/info "Encountered backpressure, retrying transaction, retry: " retry)
             (Thread/sleep tempo-msec)
             (recur (inc retry)))

         ;; unless we exceed max retries, then log error and throw.
         (and (retryable? tx-result)
              (> retry max-retries))
         (do (log/error "Max retries succeeded, could not transact: " tx " - aborting.")
             (throw (ex-info "Max retries exceeded" tx-result)))

         ;; with different anomaly, re-throw ex-info with anomaly
         :else
         (do (log/error "Encountered unexpected result")
             (throw (ex-info "Encountered unexpected transaction result" tx-result)))))))
  ([conn tx]
   (sync+retry conn tx 2000 10)))


(comment
  :single-file-pipeline-test
  (in-ns 'com.vendekagonlabs.unify.db.transact)
  (def path "/Users/bkamphaus/scratch/template-diff-clean/tx-data/import-job.edn")
  (def path "/Users/bkamphaus/scratch/template-diff-clean/tx-data/unify-ref-0001-gc_ref_cnv_1.tsv-6b4a98ae-6fe9-5b7e-b5ee-fb197dabe7cd.edn")
  (def path "/Users/bkamphaus/scratch/template-diff-clean/tx-data/unify-ref-0001-gc_ref_cnv_3.tsv-34ea5f0e-f617-5cfb-b282-05919fb436a1.edn")
  (require '[clojure.java.io :as io])

  (def part-renames
    (partial diff-renames
             {:dataset-name "template-diff-many"
              :import-name "template-diff-many-0d05dbe2"
              :diff-suffix "blah"}))

  (clojure.edn/read (PushbackReader. (io/reader (io/file path)
                                                :encoding "UTF-8")))
  (slurp path)

  (with-open [in (PushbackReader. (io/reader (io/file path)))]
    (let [input-seq (->> (repeatedly #(edn/read {:eof ::eof} in))
                         (take-while #(not= % ::eof)))]
      (doseq [tx input-seq]
        (pp/pprint (part-renames tx))))))


(comment
  (def import-ref
    '({:db/id "datomic.tx",
       :import/import
              {:import/user "your-user-name",
               :import/schema-version "0.5.0",
               :import/unify-version "0.4.83",
               :import/name "template-diff-many-0d05dbe2",
               :db/id "temp-import-ent"},
       :import/txn-id "fb7a546d-53a6-4749-8b83-9d9b9116ac38"}))

  (first import-ref)
  (update-in (first import-ref) [:import/import 0] #(str % "-" "blah")))
