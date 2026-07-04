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

(def email-send
  {:actions
   [{:type :email/send
     :subject "Mission Accepted"
     :message "I'll be in the Bahamas"}]})

(def successfully-found-email-list
  {:truncated false
   :emails [{:from "boss"
             :message "Take a vacation"}]})

(defn make-email-list-response
  [{:keys [response-event-type]}]
  {:request #{{:message successfully-found-email-list
               :type response-event-type}}})

(defn make-email-bthread
  []
  (b/on :email/list make-email-list-response))

(defn -llm-response
  [{:keys [llm-response-event-type]}]
  (let [responses [text-response find-email-list email-send]]
    {:request (into #{}
                    (map #(assoc {:type llm-response-event-type}
                                 :response %))
                    responses)}))

(defn make-llm-response-bthread
  []
  (b/on :pavlov.ai/call-llm -llm-response))

(defn make-llm-response-bthread
  []
  (b/bids [{:wait-on #{:pavlov.ai/call-llm}}
           -llm-response
           {:wait-on #{:pavlov.ai/call-llm}}
           -llm-response
           {:wait-on #{:pavlov.ai/call-llm}}
           -llm-response]))

(defn make-bthreads
  []
  {::llm-response (make-llm-response-bthread)
   ::email (make-email-bthread)})
