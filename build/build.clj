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

(defn uber [_]
  (clean nil)
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