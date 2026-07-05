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

(def call-llm-event-type
  :pavlov.ai/call-llm)

(defn make-invocation-event
  "Create the generic invocation event; the fan-out-agent-events
  function is required to retarget to specific bthreads."
  [agent-name m]
  (assoc m
         :agent-name agent-name
         :type agent-invocation-event-type))

(defn make-initialized-event
  [bthread-config]
  {:type agent-initialized-event
   :agent-name (:name bthread-config)
   :agent/config bthread-config})

(defn make-agent-response
  "Given the result from the LLM, create a response event.

  For now supports only 1 action."
  [action-response-type event] ;; should have action?
  (let [missing-event-type ::missing-event-type

        event-type
        (get-in event
                [:response :actions 0 :type]
                missing-event-type)]

    (cond-> (assoc event
                   :type event-type
                   :response-event-type action-response-type)

      (= event-type missing-event-type)
      (assoc :invariant-violated true
             :reason :missing-event-type
             :event event))))

(defn fan-out-agent-events
  "on any event-type fan out to individual events"
  ([]
   (fan-out-agent-events
    #{agent-initialized-event
      agent-invocation-event-type
      agent-response-event-type
      llm-response-event-type}))
  ([event-types]
   (b/on-any event-types
             (fn [{bthread-name :agent-name
                   retargeted-event-type :retargeted-event-type
                   event-type :type
                   :as event}]
               {:request
                #{(assoc event :type
                         (or retargeted-event-type
                             [event-type bthread-name]))}}))))
