(ns tech.thomascothran.pavlov.ai.agent.environment
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(def stop-response
  {:choices [{:finish_reason "stop"
              :message
              {:role "assistant"
               :content "hello back"}}]})

(def one-tool-call-response
  {:choices [{:finish_reason "tool_calls"
              :message
              {:role "assistant",
               :content nil,
               :refusal nil,
               :reasoning nil,
               :tool_calls
               [{:type "function",
                 :id "call_00_pBCFYBjcZm6vuGDHlAsQyh15",
                 :function
                 {:name "sort-by",
                  :arguments
                  "{\"items\": [{\"name\":\"Ada\",\"age\":36},{\"name\":\"Grace\",\"age\":30},{\"name\":\"Edsger\",\"age\":42}], \"key\": \"age\", \"descending\": false}"}}]}}]})

(def two-tool-call-response
  (assoc-in one-tool-call-response
            [:choices 0 :message :tool_calls 1]
            {:type "function"
             :id "call_01_PasdfasLKlkasdflakse"
             :function
             {:name "sum"
              :arguments "[1, 3]"}}))

(defn -llm-response
  [{:keys [llm-response-event-type] :as event}]
  {:request (into #{}
                  (map #(assoc % :type llm-response-event-type))
                  [stop-response
                   one-tool-call-response
                   #_two-tool-call-response])})

(defn make-llm-response-bthread
  []
  (b/on :pavlov.ai/call-llm -llm-response))

(defn make-bthreads
  []
  {::llm-response (make-llm-response-bthread)})
