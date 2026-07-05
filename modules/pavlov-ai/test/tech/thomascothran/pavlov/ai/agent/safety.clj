(ns tech.thomascothran.pavlov.ai.agent.safety
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(def ^:private
  missing-actions
  {:type ::missing-actions
   :invariant-violated true})

(def ^:private missing-messages
  {:type ::missing-message-history
   :invariant-violated true})

(def ^:private missing-roles
  {:type ::missing-message-roles
   :invariant-violated true})

(def ^:private missing-content
  {:type ::missing-message-content
   :invariant-violated true})

(defn make-action-spec-bthread
  []
  (b/on :pavlov.ai/call-llm
        (fn [{:keys [actions] :as event}]
          (when (empty? actions)
            {:request #{(assoc missing-actions
                               :event event)}}))))

(defn missing-roles?
  [messages]
  (->> messages
       (filter (comp (complement #{"user" "assistant" "system"})
                     :role))
       seq))

(defn missing-content?
  [messages]
  (->> messages
       (filter (comp nil? :content))
       seq))

(defn make-history-check-bthread
  []
  (b/on :pavlov.ai/call-llm
        (fn [{:keys [messages] :as event}]
          (cond (empty? messages)
                {:request #{(assoc missing-messages
                                   :event event)}}
                (missing-roles? messages)
                {:request #{(assoc missing-roles
                                   :event event)}}

                (missing-content? messages)
                {:request #{(assoc missing-roles
                                   :event event)}}))))

(defn make-bthreads
  []
  {::make-action-spec-bthread (make-action-spec-bthread)
   ::make-history-check-bthread (make-history-check-bthread)})
