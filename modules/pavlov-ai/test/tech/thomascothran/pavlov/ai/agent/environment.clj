(ns tech.thomascothran.pavlov.ai.agent.environment
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(def text-response
  {:actions
   [{:type :text-response
     :message "Hello yourself"}]})

(def find-email-list
  {:actions
   [{:type :email/list
     :lookback {:unit :minutes
                :value 20}}]})

(def successfully-found-email-list
  {:truncated false
   :emails [{:from "boss"
             :message "Take a vacation"}]})

;; TODO - handle email list call
(defn make-email-list-response
  [{:keys [response-event-type]}]
  {:request #{{:message successfully-found-email-list
               :type response-event-type}}})

(defn make-email-bthread
  []
  (b/on :email/list make-email-list-response))

(defn- action-result-message?
  [message]
  (contains? message :emails))

(defn -llm-response
  [{:keys [llm-response-event-type messages]}]
  (let [responses (if (some action-result-message? messages)
                    [text-response]
                    [text-response find-email-list])]
    {:request (into #{}
                    (map #(assoc {:type llm-response-event-type}
                                 :response %))
                    responses)}))

(defn make-llm-response-bthread
  []
  (b/on :pavlov.ai/call-llm -llm-response))

(defn make-bthreads
  []
  {::llm-response (make-llm-response-bthread)
   ::email (make-email-bthread)})
