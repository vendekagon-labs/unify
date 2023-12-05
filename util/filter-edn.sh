#!/bin/sh
#_(
   #_DEPS is same format as deps.edn. Multiline is okay.
   DEPS='
   {:deps {}}
   '

   #_You can put other options here
   OPTS='
   -J-Xms256m -J-Xmx256m -J-client
   '

exec clojure $OPTS -Sdeps "$DEPS" "$0" "$@"
)

(require '[clojure.edn :as edn])
(require '[clojure.java.io :as io])

(try
  (let [[input-file output-file & str-keywords] *command-line-args*
        keys (map #(keyword %) str-keywords)
        file (io/as-file input-file)
        from (-> file
                 (slurp)
                 (edn/read-string))
        values (reduce
                 (fn [coll k]
                   (if (contains? from k)
                     (let [v (get from k)]
                       (assoc coll k v))
                     coll))
                 {}
                 keys)]
    (spit output-file values)
  )
  (catch Exception e
    (System/exit 1)))

