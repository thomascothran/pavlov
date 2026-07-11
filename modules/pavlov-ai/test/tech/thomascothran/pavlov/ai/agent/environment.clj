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
                :value 20}}
    {:type :email/send
     :subject "Mission Accepted"
     :message "I'll be in the Bahamas"}]})

(def non-existent-action
  {:actions
   [{:type :non-existent-action}]})

(def successfully-found-email-list
  {:truncated false
   :emails [{:from "boss"
             :message "Take a vacation"}]})

(defn make-email-list-response
  [{:keys [response-event-type] :as e}]
  {:request #{{:result successfully-found-email-list
               :type response-event-type
               :invariant-violated (nil? response-event-type)}}})

(defn make-email-bthread
  []
  (b/on :email/list make-email-list-response))

(defn -llm-response
  [{:keys [llm-response-event-type]
    agent-name :agent-name}]
  (assert agent-name)
  (let [responses [text-response
                   find-email-list
                   find-email-list-with-invalid-arguments
                   email-send
                   multiple-valid-actions
                   non-existent-action]]
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
