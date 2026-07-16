(ns tech.thomascothran.pavlov.ai.event
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(def agent-invocation-event-type
  :pavlov.ai/invoke-agent)

(def agent-initialized-event
  :pavlov.ai/agent-bthread-initialized)

(def agent-response-event-type
  :pavlov.ai/agent-bthread-response)

(def llm-response-event-type
  :pavlov.ai/llm-response)

(def action-response-event-type
  :pavlov.ai/action-response)

(def action-rejected-event-type
  :pavlov.ai/action-rejected)

(def call-llm-event-type
  :pavlov.ai/call-llm)

(defn make-invocation-event
  "Create the generic invocation event; the fan-out-agent-events
  function is required to retarget to specific bthreads."
  [agent-name m]
  (assoc m
         :agent-name agent-name
         :type [agent-invocation-event-type agent-name]))

(defn make-initialized-event
  [bthread-config]
  {:agent-name (:name bthread-config)
   :type [agent-initialized-event (:name bthread-config)]
   :agent/config bthread-config})

(defn make-action-rejected-event-type
  [agent-name]
  [action-rejected-event-type agent-name])

(defn make-action-rejected-event
  [agent-name violations]
  {:type [action-rejected-event-type agent-name]
   :agent-name agent-name
   :violations violations})

(defn make-agent-response
  "Given the result from the LLM, create a response event.

  Action-response-type is a function that takes the action type
  and returns the type for the response to that action"
  [action-response-type event] ;; should have action?
  (let [missing-event-type ::missing-event-type]
    (into []
          (comp (map #(assoc % :response-event-type
                             (action-response-type (:type %))))
                (map #(if (nil? (:type %))
                        (assoc % :invariant-violated true
                               :type missing-event-type)
                        %)))
          (get-in event [:response :actions]))))

(defn make-action-response-type
  [agent-name]
  [action-response-event-type agent-name])

(comment
  ;; The current `make-agent-response` preserves the original LLM response
  ;; envelope and only changes the top-level event type. That means action
  ;; arguments stay nested under [:response :actions 0 ...].
  (def llm-email-list-response
    {:type [:pavlov.ai/llm-response :happy-path]
     :agent-name :happy-path
     :response {:actions [{:type :email/list
                           :lookback {:unit :minutes
                                      :value 20}}]}})

  (make-agent-response (constantly [:pavlov.ai/action-response :happy-path])
                       llm-email-list-response))
