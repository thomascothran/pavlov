(ns tech.thomascothran.pavlov.ai.agent.environment
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.ai.event :as aie]))

(def text-response
  {:actions
   [{:type :text-response
     :response "Hello yourself"}]})

(def find-email-list
  {:actions
   [{:type :email/list
     :lookback {:unit :minutes
                :value 20}}]})

(def find-email-list-with-invalid-arguments
  {:actions
   [{:type :email/list
     :lookback {:unit :minutes
                :value "twenty"}}]})

(def email-send
  {:actions
   [{:type :email/send
     :subject "Mission Accepted"
     :message "I'll be in the Bahamas"}]})

(def multiple-valid-actions
  {:actions
   [{:type :email/list
     :lookback {:unit :minutes
                :value 21}}
    {:type :email/send
     :subject "Multiple action response"
     :message "This action must not be forwarded"}]})

(def non-existent-action
  {:actions
   [{:type :non-existent-action}]})

(def successfully-found-email-list
  {:truncated false
   :emails [{:from "boss"
             :message "Take a vacation"}]})

(defn make-email-list-response
  [{:keys [response-event-type
           llm-call-id]}]
  {:request #{{:result successfully-found-email-list
               :type response-event-type
               :llm-call-id llm-call-id
               :invariant-violated (nil? response-event-type)}}})

(defn make-email-bthread
  []
  (b/on :email/list make-email-list-response))

(defn -llm-response
  [{:keys [llm-response-event-type llm-call-id]
    agent-name :agent-name}]
  (assert agent-name)
  (assert llm-call-id)
  (let [responses [text-response
                   find-email-list
                   find-email-list-with-invalid-arguments
                   email-send
                   multiple-valid-actions
                   non-existent-action]]
    {:request (into #{}
                    (map #(assoc {:type llm-response-event-type}
                                 :llm-call-id llm-call-id
                                 :agent-name agent-name
                                 :response %))
                    responses)}))

(defn make-llm-response-bthread
  []
  (b/bids [{:wait-on #{aie/call-llm-event-type}}
           -llm-response
           {:wait-on #{aie/call-llm-event-type}}
           -llm-response
           {:wait-on #{aie/call-llm-event-type}}
           -llm-response]))

(defn make-bthreads
  []
  {::llm-response (make-llm-response-bthread)
   ::email (make-email-bthread)})
