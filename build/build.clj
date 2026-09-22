(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.vendekagonlabs.unify.cli)

(def version (format "0.2.%s" (b/git-count-revs nil)))


(def class-dir "target/classes")

(def uber-file
  (format "target/%s-%s-alpha.jar" (name lib) version))

(def basis
  (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn write-version-info!
  "Writes resources/info.edn with the current version (0.2.<git rev count>).
  Called automatically by uber below, so a built jar's embedded version can
  never drift from the commit it's actually built from -- that's the one
  place version and info.edn are computed, so they can't get out of sync.
  Also callable standalone (clj -X:build write-version-info!) to refresh it
  for local dev/REPL use without doing a full build."
  [_]
  (binding [*print-namespace-maps* false]
    (spit "resources/info.edn" (pr-str {:unify/version version}))))

(defn uber [_]
  (clean nil)
  (write-version-info! nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile '[com.vendekagonlabs.unify.cli]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'unify-central.service}))

(defn print-version [_]
  (print version))

(comment
  (uber nil))