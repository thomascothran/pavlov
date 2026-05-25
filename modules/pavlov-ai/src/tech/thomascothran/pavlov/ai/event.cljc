(ns tech.thomascothran.pavlov.ai.event
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(def agent-invocation-event-type
  :pavlov.ai/invoke-agent)

(def agent-initialized-event
  :pavlov.ai/agent-bthread-initialized)

(def agent-response-event-type
  :pavlov.ai/agent-bthread-response)

(defn make-invocation-event
  [agent-name m]
  (assoc m
         :agent/name agent-name
         :type agent-invocation-event-type))

(defn make-initialized-event
  [bthread-config]
  {:type agent-initialized-event
   :agent/name (:name bthread-config)
   :agent/config bthread-config})

(defn make-agent-response
  [agent-name m]
  (assoc m
         :type agent-response-event-type
         :agent/name agent-name
         :response-event-type [agent-invocation-event-type
                               agent-name]))

(defn fan-out-agent-events
  "on any event-type fan out to individual events"
  ([]
   (fan-out-agent-events
    #{agent-initialized-event
      agent-invocation-event-type
      agent-response-event-type}))
  ([event-types]
   (b/on-any event-types
             (fn [{bthread-name :agent/name
                   event-type :type
                   :as event}]
               {:request
                #{(assoc event :type
                         [event-type bthread-name])}}))))
