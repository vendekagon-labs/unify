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
(ns com.vendekagonlabs.unify.db.common
  (:require [cognitect.anomalies :as anomalies]
            [clojure.string :refer [includes? lower-case]]
            [clojure.tools.logging :as log])
  (:import (clojure.lang ExceptionInfo)))

(defn retryable?
  "True if the anomaly in resp indicates Datomic is busy, indicating op should be retried."
  [resp]
  (let [category (::anomalies/category resp)
        message (::anomalies/message resp)]
    (when (and (some? category) (some? message))
      (log/info "retryable?> category: " category)
      (log/info "retryable?> message: " message))
    (or (= ::anomalies/busy category)
        (= ::anomalies/unavailable category)
        (= ::anomalies/interrupted category)
        (and (= 500 (:datomic.client/http-error-status resp))
             (do (log/warn "Retrying a 500 error. If this retry loop continues with 500, abort process.")
                 true))
        (#{429 503} (:datomic.client/http-error-status resp))

        ;; CDEL-452
        ;;
        ;; clojure.lang.ExceptionInfo: Error communicating with HOST 0.0.0.0 or ALT_HOST ec2-18-212-238-161.compute-1.amazonaws.com on PORT 4334
        ;; at datomic.connector$endpoint_error.invokeStatic(connector.clj:53)
        ;; at datomic.connector$endpoint_error.invoke(connector.clj:50)
        ;; at datomic.connector.TransactorHornetConnector$fn__10163.invoke(connector.clj:224)
        ;; at datomic.connector.TransactorHornetConnector.admin_request_STAR_(connector.clj:212)
        ;; at datomic.peer.Connection$fn__10425.invoke(peer.clj:239)
        ;;
        ;; Caused by: org.apache.activemq.artemis.api.core.ActiveMQNotConnectedException: AMQ119010: Connection is destroyed
        ;; at org.apache.activemq.artemis.core.protocol.core.impl.ChannelImpl.sendBlocking(ChannelImpl.java:335)
        ;; at org.apache.activemq.artemis.core.protocol.core.impl.ChannelImpl.sendBlocking(ChannelImpl.java:315)
        ;; at org.apache.activemq.artemis.core.protocol.core.impl.ActiveMQClientProtocolManager.createSessionContext(ActiveMQClientProtocolManager.java:288)
        ;; at org.apache.activemq.artemis.core.protocol.core.impl.ActiveMQClientProtocolManager.createSessionContext(ActiveMQClientProtocolManager.java:237)
        ;; at org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryImpl.createSessionChannel(ClientSessionFactoryImpl.java:1284)
        ;;
        (and (some? message)
             (includes? (lower-case message) "transactor")))))

(defn ->result-or-anomaly
  "Try a datomic api call, return the result if expected, an anomaly if one is
  produced, or re-throw if neither of those two cases."
  [datomic-api-call args]
  (try
    (apply datomic-api-call args)
    (catch ExceptionInfo e
      (if-let [anomaly (-> e ex-data ::anomalies/category)]
        (ex-data {:exception e
                  :anomaly   anomaly})
        (do
          (log/error "Datomic API call encountered exception without any expected anomaly.")
          (throw e))))))
