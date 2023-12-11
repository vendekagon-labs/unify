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
(ns com.vendekagonlabs.unify.import.engine.parse.matrix
  (:require [com.vendekagonlabs.unify.import.engine.parse.data :as parse.data]
            [com.vendekagonlabs.unify.db.metamodel :as metamodel]
            [com.vendekagonlabs.unify.util.collection :as util.coll]
            [com.vendekagonlabs.unify.util.uuid :as util.uuid]
            [com.vendekagonlabs.unify.import.engine.parse.matrix-file :as parse.matrix-file]))


(defn matrix->entity
  "Given a directive for parsing a matrix file, parses the file and returns an entity
  map representation of the matrix blob corresponding to the given matrix spec + file
  content combination. The entity representation contains reference data for all
  measurement references and targets, but does not contain the numeric matrix
  representation.

  Along with the entity map representation, matrix->entity also returns :unify.matrix/
  namespaced keyword used by downstream processing (matrix file prepare and upload)."
  [{:keys [parsed-cfg schema mapping] :as job-context}
   {:keys [unify.matrix/input-file
           unify.matrix/format
           unify.matrix/column-attribute                    ;; only dense, otherwise all in indexed-by
           unify.matrix/indexed-by
           unify/node-kind] :as mtx-directive}]
  (let [data-type-key (metamodel/matrix-data-type-attr schema node-kind)
        value-type (get mtx-directive data-type-key)
        db-value-type (metamodel/attr->db-type schema value-type)
        data-type-kv {data-type-key value-type}
        _ (when-not (and input-file format indexed-by value-type)
            (throw (ex-info (str "Every matrix directive requires at minimum :unify.matrix prefixed:"
                                 "input-file, format, indexed-by, and a measurement/data type specification.")
                            {::matrix-directive mtx-directive})))
        resolved-constants (parse.data/extract-constant-data job-context mtx-directive #{})
        parsed-mtx-file (parse.matrix-file/parse-matrix-file
                          input-file
                          ;; default to sparse
                          (merge {:indexed-by (util.coll/remove-keys-by-ns indexed-by "unify")
                                  :data-spec  value-type
                                  :data-type  db-value-type
                                  :sparse?    true}
                                 ;; when not sparse, add column-attribute/target
                                 ;; for dense matrix column interunifyation
                                 (when (not= :unify.matrix.format/sparse format)
                                   {:sparse? false
                                    :target  column-attribute})))
        no-unify-parsed-mtx-file (util.coll/remove-keys-by-ns parsed-mtx-file "unify")
        raw-entity (util.coll/remove-keys-by-ns mtx-directive "unify")
        resolve-fn (fn [attr val]
                     (parse.data/resolve-value parsed-cfg schema mapping mtx-directive attr val))
        resolved-entity (into {} (for [[attr v] raw-entity]
                                   [attr (if (coll? v)
                                           (map (partial resolve-fn attr) v)
                                           (resolve-fn attr v))]))
        resolved-refs (into {} (for [[ind vals] no-unify-parsed-mtx-file]
                                 [ind (map (partial resolve-fn ind) vals)]))
        key-pt1 (util.uuid/random)
        key (str key-pt1 ".tsv")
        key-attr (metamodel/matrix-key-attr schema node-kind)
        precomputed (:unify/precomputed mtx-directive)
        _ (when-not key-attr
            (throw (ex-info (str "Could not resolve backing file attribute for: " input-file)
                            {::job mtx-directive})))
        backing-key {key-attr key}
        entity-map (merge resolved-entity
                          precomputed
                          no-unify-parsed-mtx-file
                          resolved-refs
                          backing-key
                          data-type-kv
                          resolved-constants
                          {:unify.matrix/input-file  input-file
                           :unify.matrix/output-file key
                           :unify.matrix/header-substitutions
                           (-> (util.coll/remove-keys-by-ns indexed-by "unify")
                               (merge (when (= :unify.matrix.format/sparse format)
                                        {(:unify.matrix/sparse-value-column parsed-mtx-file)
                                         value-type})))})
        ;; self-uid comes in last, potentially dependent on prior entity parsing stuff.
        self-uid (parse.data/create-self-uid parsed-cfg schema mtx-directive entity-map)
        validation-errors (seq (:validation-errors entity-map))]
    (if validation-errors
      (throw (ex-info "Measurements in matrix file did not pass spec!"
                      {::spec              value-type
                       ::validation-errors (take 1000 validation-errors)}))
      (-> entity-map
          (merge self-uid)
          (merge {:unify.matrix/constant-columns
                  (util.coll/remove-keys-by-ns (:unify.matrix/constants mtx-directive) "unify")})
          (dissoc :validation-errors)))))
