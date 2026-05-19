(ns tech.thomascothran.pavlov.ai.agent
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as event]))

;; Ok -- so do we register ourselves? I think so.
(defn config->initial-bid
  [{:keys [initialized-event
           invocation-event]}]
  {:request #{{:type initialized-event}}
   :wait-on #{invocation-event}})

(defn make-llm-event
  [llm-response-event-type
   {:keys [message-history llm-calls]
    :or {message-history []
         llm-calls 0}
    :as _state}
   {:keys [message] :as _event}]
  (let [llm-calls' (inc llm-calls)]
    {:type :pavlov.ai/call-llm
     :llm-response-event-type llm-response-event-type
     :llm-calls llm-calls'
     :messages (conj message-history message)}))

(defn llm-invocation->bid
  [state event llm-response-event-type default-waits]
  (let [{:keys [messages llm-calls] :as llm-event}
        (make-llm-event llm-response-event-type state event)
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
  (let [invocation-event (:invocation-event config)
        agent-name (:name config)
        llm-response-event-type [:pavlov.ai/llm-response agent-name]
        default-waits #{invocation-event llm-response-event-type}]
    (b/step
     (fn [state event]
       (let [event-type (event/type event)]
         (cond
           (nil? event-type)
           [config (config->initial-bid config)]

           ;; invoke llm
           (= event-type invocation-event)
           (llm-invocation->bid state event llm-response-event-type default-waits)

           ;; handle llm response
           ;; TODO - handle tool calls!
           (= event-type llm-response-event-type)
           [state {:request #{{:type (:response-event-type config)
                               :response (get-in event [:choices 0 :message :content])}}}]

           :else
           [state {:wait-on default-waits}]))))))
