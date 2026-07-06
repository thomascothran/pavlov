(ns tech.thomascothran.pavlov.ai.agent
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.ai.event
             :refer [agent-invocation-event-type
                     make-initialized-event
                     make-agent-response
                     llm-response-event-type
                     action-response-event-type
                     call-llm-event-type]]))

(defn make-llm-event
  [agent-config llm-response-event-type
   {:keys [message-history llm-calls]
    :or {message-history []
         llm-calls 0}
    :as _state}
   message]
  (assert (map? agent-config))
  (let [agent-name (get agent-config :name)
        actions (get agent-config :actions)
        llm-calls' (inc llm-calls)]
    {:type call-llm-event-type
     :agent-name agent-name
     :actions actions
     :llm-response-event-type llm-response-event-type
     :llm-calls llm-calls'
     :messages (conj message-history message)}))

(defn llm-invocation->
  "Produce the bid that invokes the LLM"
  [agent-config state event llm-response-event-type default-waits]
  (let [{:keys [messages llm-calls] :as llm-event}
        (make-llm-event agent-config llm-response-event-type state (:message event))
        state (assoc state
                     :message-history messages
                     :llm-calls llm-calls)
        bid {:wait-on default-waits
             :request #{llm-event}}]
    [state bid]))

(defn action-result->
  "Produce the bid from the action result"
  [agent-config state {:keys [result] :as event}
   llm-response-event-type default-waits]
  (let [message {:content result
                 :role "user"}
        {:keys [messages llm-calls] :as llm-event}
        (make-llm-event agent-config llm-response-event-type state message)
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
           (llm-invocation-> config state event llm-response-event-type default-waits)

           ;; llm responses
           (= event-type llm-response-event-type)
           [(update state :message-history conj
                    {:content (:response event)
                     :role "assistant"})
            {:request (make-agent-response
                       (constantly action-response-type)
                       event)}]

           ;; action responses
           (= event-type action-response-type)
           (action-result-> config state event llm-response-event-type default-waits)

           :else
           [state {:wait-on default-waits}]))))))
