(ns tech.thomascothran.pavlov.ai.agent
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.ai.event
             :refer [agent-invocation-event-type
                     make-initialized-event
                     make-agent-response
                     llm-response-event-type
                     action-response-event-type]]))

(defn make-llm-event
  [agent-name llm-response-event-type
   {:keys [message-history llm-calls]
    :or {message-history []
         llm-calls 0}
    :as _state}
   {:keys [message] :as _event}]
  (let [llm-calls' (inc llm-calls)]
    {:type :pavlov.ai/call-llm
     :agent-name agent-name
     :llm-response-event-type llm-response-event-type
     :llm-calls llm-calls'
     :messages (conj message-history message)}))

(defn llm-invocation->bid
  [agent-name state event llm-response-event-type default-waits]
  (let [{:keys [messages llm-calls] :as llm-event}
        (make-llm-event agent-name llm-response-event-type state event)
        state (assoc state
                     :message-history messages
                     :llm-calls llm-calls)
        bid {:wait-on default-waits
             :request #{llm-event}}]
    [state bid]))

(defn make-bthread
  [config]
  (assert (:name config)
          "Agent must have a name")
  (let [agent-name (:name config)
        invocation-event [agent-invocation-event-type agent-name]
        llm-response-event-type [llm-response-event-type agent-name]
        ;; Assumes that we only have one tool call outstanding at a time
        action-response-type [action-response-event-type agent-name]
        default-waits #{invocation-event llm-response-event-type action-response-type}]
    (b/step
     (fn [state event]
       (let [event-type (event/type event)]
         (cond
           (nil? event-type)
           [config
            {:request #{(make-initialized-event config)}
             :wait-on default-waits}]

           ;; invoke llm
           (= event-type invocation-event)
           (llm-invocation->bid agent-name state event llm-response-event-type default-waits)

           ;; llm responses
           (= event-type llm-response-event-type)
           [state {:request
                   #{(make-agent-response action-response-type event)}}]

           ;; action responses
           (= event-type action-response-type)
           (llm-invocation->bid agent-name state event llm-response-event-type default-waits)

           :else
           [state {:wait-on default-waits}]))))))
