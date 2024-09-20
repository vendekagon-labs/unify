(ns com.vendekagonlabs.unify.import.engine.parse.config.yaml-test
  (:require [clojure.test :refer :all]
            [com.vendekagonlabs.unify.util.io :as util.io]
            [clojure.data :as data]
            [com.vendekagonlabs.unify.import.engine.parse.config.yaml :as sut]))

(def config-yaml-file
  "test/resources/systems/candel/parse-config-examples/template-config.yaml")
(def ref-config-edn-file
  "test/resources/systems/candel/template-dataset/config.edn")

(def mapping-yaml-file
  "test/resources/systems/candel/parse-config-examples/template-mappings.yaml")
(def ref-mapping-edn-file
  "test/resources/systems/candel/template-dataset/mappings.edn")

(deftest equivalent-config-test
  (let [parsed-yaml-config (sut/read-config-file config-yaml-file)
        parsed-edn-config (util.io/read-edn-file ref-config-edn-file)
        diff (data/diff parsed-edn-config parsed-yaml-config)]
    (testing "YAML and edn equivalent configs parse to equivalent edn data structures."
      (is (= parsed-edn-config parsed-yaml-config))
      ;; one of these will always fail when equality fails, this will cause failure
      ;; report to show the diff data structure for what's not equivalent
      (is (nil? (first diff)))
      (is (nil? (second diff))))))

(deftest equivalent-mapping-test
  (let [parsed-yaml-mapping (sut/read-mappings-file mapping-yaml-file)
        parsed-edn-mapping (util.io/read-edn-file ref-mapping-edn-file)
        diff (data/diff parsed-edn-mapping parsed-yaml-mapping)]
    (testing "YAML and edn equivalent configs parse to equivalent edn data structures."
      (is (= parsed-edn-mapping parsed-yaml-mapping))
      ;; one of these will always fail when equality fails, this will cause failure
      ;; report to show the diff data structure for what's not equivalent
      (is (nil? (first diff)))
      (is (nil? (second diff))))))


(comment
  (run-tests *ns*))