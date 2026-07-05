(ns tech.thomascothran.pavlov.ai.agent.environment
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.ai.event :as aie]))

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
  [{:keys [response-event-type] :as e}]
  {:request #{{:message successfully-found-email-list
               :type response-event-type
               :invariant-violated (nil? response-event-type)}}})

(defn make-email-bthread
  []
  (b/on :email/list make-email-list-response))

(defn -llm-response
  [{:keys [llm-response-event-type]
    agent-name :agent-name}]
  (assert agent-name)
  (let [responses [text-response find-email-list email-send]]
    {:request (into #{}
                    (map #(assoc {:type aie/llm-response-event-type
                                  ;; for fan-out retargeting
                                  :agent-name agent-name
                                  :retargeted-event-type llm-response-event-type}
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
