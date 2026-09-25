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
(ns com.vendekagonlabs.unify.util.progress
  "A small nextflow-inspired progress reporter, used for the prepare and
  tx-data-generation stages: an overall bar for the stage plus a bounded set
  of live per-file lines, redrawn in place on a real terminal. Falls back to
  periodic plain-text snapshots (no cursor movement, no color) when stdout
  isn't a terminal -- piped to a file, CI, etc. -- so we never corrupt a log
  with raw ANSI escapes.

  Usage: wrap a stage in `with-stage`, and within it, `register!` each file
  with its (approximate) total up front, `tick!` as records/entities are
  processed, and `complete!`/`fail!` when a file finishes. See
  `fast-line-count` for how an approximate total is produced cheaply."
  (:require [com.vendekagonlabs.unify.util.term :as term]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [clojure.string :as str])
  (:import (java.util.concurrent.atomic AtomicLong)))

(defn fast-line-count
  "Approximate row/entity count for a file: a raw scan counting newlines.
  Used only to size progress bars/ETAs, so this doesn't need to be exact (or
  quote-aware like a real CSV/EDN parse) -- just fast. Returns nil (meaning
  'unknown total') if the file can't be read for any reason."
  [path]
  (try
    (with-open [rdr (util.io/reader (str path))]
      (let [buf (char-array 65536)]
        (loop [total (long 0)]
          (let [n (.read rdr buf)]
            (if (neg? n)
              total
              (recur (long (loop [i (int 0) c total]
                             (if (= i n)
                               c
                               (recur (unchecked-inc-int i)
                                      (if (= (aget buf i) \newline) (unchecked-inc c) c)))))))))))
    (catch Exception _ nil)))

(def ^:private max-visible-files
  "Cap on how many currently-running files get their own line, so the display
  stays bounded even when a stage's worker/file-concurrency count is high."
  8)

(def ^:private bar-width 20)
(def ^:private redraw-interval-ms 200)
(def ^:private plain-snapshot-interval-ms 4000)

(defonce ^:private files (atom {}))
(defonce ^:private order (atom []))
(defonce ^:private stage-info (atom nil))
(defonce ^:private renderer (atom nil))

(defonce ^:private completed-order (atom []))

(defn register!
  "Registers a file as starting processing under `label`, with an
  (approximate, possibly nil/unknown) total unit count."
  [label total]
  (swap! files assoc label {:total     total
                            :done      (AtomicLong. 0)
                            :status    (atom :running)
                            :start-ms  (System/currentTimeMillis)})
  (swap! order conj label))

(defn tick!
  "Advances label's progress by n (default 1) units."
  ([label] (tick! label 1))
  ([label n]
   (when-let [f (get @files label)]
     (.addAndGet ^AtomicLong (:done f) (long n)))))

(defn complete! [label]
  (when-let [f (get @files label)]
    (reset! (:status f) :done))
  (swap! completed-order conj label))

(defn fail! [label]
  (when-let [f (get @files label)]
    (reset! (:status f) :failed))
  (swap! completed-order conj label))

;; Rendering ----------------------------------------------------------------

(defn- fmt-count [n]
  (cond
    (nil? n) "?"
    (>= n 1000000) (format "%.1fm" (/ n 1000000.0))
    (>= n 1000) (format "%.1fk" (/ n 1000.0))
    :else (str n)))

(defn- fmt-duration [ms]
  (let [s (long (/ (max 0 ms) 1000))]
    (cond
      (< s 1) "<1s"
      (< s 60) (str s "s")
      (< s 3600) (format "%dm%02ds" (quot s 60) (rem s 60))
      :else (format "%dh%02dm" (quot s 3600) (rem (quot s 60) 60)))))

(defn- truncate [s width]
  (if (> (count s) width)
    (str (subs s 0 (dec width)) "…")
    (format (str "%-" width "s") s)))

(defn- bar-str [frac]
  (let [filled (max 0 (min bar-width (int (Math/round (* (double frac) bar-width)))))]
    (str (apply str (repeat filled \█)) (apply str (repeat (- bar-width filled) \░)))))

(defn- file-snapshot [[label {:keys [total done status start-ms]}]]
  (let [d (.get ^AtomicLong done)
        cur-status @status
        done? (= cur-status :done)
        ;; totals are an approximate line-count-based estimate (see fast-line-count),
        ;; so the real final tick count can under- or overshoot it (NA-filtered rows,
        ;; header lines, molten-data expansion, etc.). Once a file is actually done,
        ;; report it as done/done so the bar reads 100% instead of stalling at
        ;; whatever fraction the estimate happened to produce.
        eff-total (if done? d total)
        frac (cond
               done? 1.0
               (and eff-total (pos? eff-total)) (min 1.0 (/ (double d) eff-total))
               :else nil)
        elapsed-ms (- (System/currentTimeMillis) start-ms)
        eta-ms (when (and frac (pos? frac) (< frac 1.0))
                 (long (* (/ elapsed-ms frac) (- 1.0 frac))))]
    {:label label :status cur-status :frac frac :done d :total eff-total
     :elapsed-ms elapsed-ms :eta-ms eta-ms}))

(defn- render-file-line [enabled? {:keys [label status frac done total elapsed-ms eta-ms]}]
  (let [status-color (case status :done term/done :failed term/error term/info)
        label-str (term/paint enabled? [(term/fg status-color)] (truncate label 30))
        bar (if frac
              (term/paint enabled? [(term/fg term/teal)] (str "[" (bar-str frac) "]"))
              (str "[" (apply str (repeat bar-width \·)) "]"))
        pct (if frac (format "%3d%%" (int (* frac 100))) " ?? ")
        counts (str (fmt-count done) (when total (str "/" (fmt-count total))))
        right (case status
                :done "done"
                :failed (term/paint enabled? [(term/fg term/error)] "failed")
                (if eta-ms (str "~" (fmt-duration eta-ms) " left") (fmt-duration elapsed-ms)))]
    (str "  " label-str "  " bar "  " pct "  (" counts ")  " right)))

(defn- overall-line [enabled? snaps]
  (let [known-total (->> snaps (keep :total) (reduce + 0))
        known-done (->> snaps (filter :total) (map :done) (reduce + 0))
        n-done (count (filter #(= :done (:status %)) snaps))
        n-failed (count (filter #(= :failed (:status %)) snaps))
        n-total (count snaps)
        frac (when (pos? known-total) (min 1.0 (/ (double known-done) known-total)))
        {:keys [stage started-ms]} @stage-info
        elapsed-ms (- (System/currentTimeMillis) started-ms)
        bar (if frac
              (term/paint enabled? [(term/fg term/copper) term/bold] (str "[" (bar-str frac) "]"))
              (str "[" (apply str (repeat bar-width \·)) "]"))
        pct (if frac (format "%3d%%" (int (* frac 100))) " ?? ")]
    (str (term/paint enabled? [term/bold] stage) "  " bar "  " pct
         "  (files " n-done "/" n-total (when (pos? n-failed) (str ", " n-failed " failed")) ")"
         "  " (fmt-duration elapsed-ms) " elapsed")))

(defn- visible-snapshots
  "Which files get their own line: all currently-running ones, plus enough
  of the most recently *completed* (not most recently started) files to fill
  out max-visible-files. Using completion order (rather than registration
  order) matters once more files have been registered than fit on screen --
  otherwise a file that finished early gets pushed out by files that have
  merely started since, not by files that finished more recently, and its
  completion never gets rendered at all."
  [snaps]
  (let [running (filter #(= :running (:status %)) snaps)
        by-label (into {} (map (juxt :label identity) snaps))
        recent-done-labels (->> @completed-order
                                (reverse)
                                (distinct)
                                (take (max 0 (- max-visible-files (count running))))
                                (reverse))
        recent-done (keep by-label recent-done-labels)]
    (take max-visible-files (concat running recent-done))))

(defn- render-block [enabled?]
  (let [labels @order
        snaps (map (fn [l] (file-snapshot [l (get @files l)])) labels)
        visible (visible-snapshots snaps)]
    (cons (overall-line enabled? snaps)
          (map (partial render-file-line enabled?) visible))))

(defn- redraw! [prev-line-count]
  (let [lines (render-block true)
        n (count lines)]
    (when (pos? prev-line-count)
      (print (str "\u001b[" prev-line-count "A")))
    (doseq [l lines]
      (print (str "\u001b[2K" l "\n")))
    (flush)
    n))

(defn- plain-snapshot! []
  (println (overall-line false (map (fn [l] (file-snapshot [l (get @files l)])) @order)))
  (flush))

(defonce ^:private last-line-count (atom 0))

(defn- run-renderer! [stop?]
  (if (term/color-enabled?)
    (loop [prev (long 0)]
      (when-not @stop?
        (let [n (long (redraw! prev))]
          (reset! last-line-count n)
          (Thread/sleep ^long redraw-interval-ms)
          (recur n))))
    (loop []
      (when-not @stop?
        (plain-snapshot!)
        (Thread/sleep ^long plain-snapshot-interval-ms)
        (recur)))))

(defn start-stage!
  "Begins a new progress stage under `label` (e.g. \"Preparing import data\").
  Resets any prior stage's file registry."
  [label]
  (reset! files {})
  (reset! order [])
  (reset! completed-order [])
  (reset! last-line-count 0)
  (reset! stage-info {:stage label :started-ms (System/currentTimeMillis)})
  (let [stop? (atom false)
        t (Thread. ^Runnable (fn [] (run-renderer! stop?)))]
    (.setDaemon t true)
    (reset! renderer {:stop? stop? :thread t})
    (.start t)))

(defn end-stage!
  "Stops the renderer and prints one final, complete snapshot in its place.
  Uses last-line-count (not 0) as the redraw's prior-frame size, so this
  overwrites the renderer thread's last in-progress frame instead of leaving
  it on screen and printing a second, final frame below it -- the .join
  above guarantees the renderer thread is stopped (so last-line-count is no
  longer changing) before this reads it."
  []
  (when-let [{:keys [stop? thread]} @renderer]
    (reset! stop? true)
    (.join ^Thread thread 1000))
  (if (term/color-enabled?)
    (do (redraw! @last-line-count) (println))
    (plain-snapshot!))
  (reset! renderer nil))

(defmacro with-stage
  "Runs body with a progress stage of `label` active, ensuring the renderer
  is always stopped (and a final snapshot printed) afterward."
  [label & body]
  `(do
     (start-stage! ~label)
     (try
       ~@body
       (finally
         (end-stage!)))))
