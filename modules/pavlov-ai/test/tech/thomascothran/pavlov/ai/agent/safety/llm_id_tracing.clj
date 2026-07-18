(ns tech.thomascothran.pavlov.ai.agent.safety.llm-id-tracing
  "Safety monitoring for correlation IDs across LLM calls and their actions.

  The monitor keeps LLM responses separate from action lifecycles:

  - `:llm-responses` contains `[response-event-type llm-call-id]` pairs.
  - `:actions` is keyed by `[action-type llm-call-id]` and records whether
    each action is awaiting its request event or its response event."
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]))

(def ^:private call-llm-event-type :pavlov.ai/call-llm)

(def ^:private mismatched-call-id-event-type
  :tech.thomascothran.pavlov.ai.agent.safety/mismatched-call-id)

(def ^:private ambiguous-action-response-event-type
  :tech.thomascothran.pavlov.ai.agent.safety/ambiguous-action-response)

(defn- pending-event-types
  "Return the event types that can complete a pending LLM or action correlation."
  [{:keys [llm-responses actions]}]
  (into #{call-llm-event-type}
        (concat
         (map first llm-responses)
         (map (fn [[[action-type _llm-call-id]
                    {:keys [stage response-event-type]}]]
                (if (= :request stage)
                  action-type
                  response-event-type))
              actions))))

(defn- continue-monitoring
  "Build the next bid, optionally reporting a terminal safety violation."
  ([state]
   {:wait-on (pending-event-types state)})
  ([state violation]
   (cond-> (continue-monitoring state)
     violation (assoc :request #{violation}))))

(defn- call-id-mismatch
  "Describe an event whose call ID does not belong to a pending correlation."
  [expected-llm-call-ids actual-llm-call-id event]
  {:type mismatched-call-id-event-type
   :terminal true
   :invariant-violated true
   :actual-llm-call-id actual-llm-call-id
   :expected-llm-call-ids expected-llm-call-ids
   :event event})

(defn- llm-responses-addressed-by
  "Return pending LLM responses addressed by the selected event type."
  [llm-responses event-type]
  (filter #(= event-type (first %)) llm-responses))

(defn- actions-addressed-by
  "Return pending actions at STAGE addressed by the selected event type."
  [actions stage event-type]
  (filter (fn [[[action-type _llm-call-id] action-state]]
            (and (= stage (:stage action-state))
                 (= event-type
                    (if (= :request stage)
                      action-type
                      (:response-event-type action-state)))))
          actions))

(defn- expected-call-ids
  "Return the call IDs from pending correlation entries."
  [entries]
  (into #{} (map (comp second first)) entries))

(defn- start-monitoring
  "Initialize the correlation indexes and wait for the first LLM call."
  []
  (let [state {:llm-responses #{}
               :actions {}}]
    [state (continue-monitoring state)]))

(defn- remember-llm-call
  "Remember the response event and ID expected for an emitted LLM call."
  [state {:keys [llm-response-event-type llm-call-id]}]
  (assert llm-response-event-type)
  (assert llm-call-id)
  (let [next-state (update state
                           :llm-responses
                           conj
                           [llm-response-event-type llm-call-id])]
    [next-state (continue-monitoring next-state)]))

(defn- remember-response-actions
  "Replace a completed LLM response correlation with its expected actions."
  [state event pending-response-keys]
  (let [event-type (e/type event)
        actual-llm-call-id (:llm-call-id event)
        expected-llm-call-ids (into #{} (map second) pending-response-keys)
        mismatch (when-not (expected-llm-call-ids actual-llm-call-id)
                   (call-id-mismatch expected-llm-call-ids
                                     actual-llm-call-id
                                     event))
        action-entries
        (into {}
              (map (fn [action]
                     [[(e/type action) actual-llm-call-id]
                      {:stage :request}]))
              (get-in event [:response :actions]))
        next-state
        (if mismatch
          state
          (-> state
              (update :llm-responses disj
                      [event-type actual-llm-call-id])
              (update :actions merge action-entries)))]
    [next-state (continue-monitoring next-state mismatch)]))

(defn- await-action-response
  "Advance a matching action from awaiting its request to awaiting its response."
  [state event pending-action-requests]
  (let [event-type (e/type event)
        actual-llm-call-id (:llm-call-id event)
        expected-llm-call-ids (expected-call-ids pending-action-requests)
        action-key [event-type actual-llm-call-id]
        mismatch (when-not (contains? (:actions state) action-key)
                   (call-id-mismatch expected-llm-call-ids
                                     actual-llm-call-id
                                     event))
        next-state
        (if mismatch
          state
          (assoc-in state
                    [:actions action-key]
                    {:stage :response
                     :response-event-type (:response-event-type event)}))]
    [next-state (continue-monitoring next-state mismatch)]))

(defn- responses-correlated-to
  "Return action responses correlated by call ID and, when present, action type."
  [event pending-action-responses]
  (let [actual-llm-call-id (:llm-call-id event)]
    (filter (fn [[[action-type llm-call-id] _action-state]]
              (and (= llm-call-id actual-llm-call-id)
                   (or (nil? (:action-type event))
                       (= action-type (:action-type event)))))
            pending-action-responses)))

(defn- complete-action
  "Complete the one pending action identified by an action response event."
  [state event pending-action-responses]
  (let [actual-llm-call-id (:llm-call-id event)
        matching-responses (responses-correlated-to event
                                                    pending-action-responses)
        expected-llm-call-ids (expected-call-ids pending-action-responses)
        violation
        (cond
          (empty? matching-responses)
          (call-id-mismatch expected-llm-call-ids
                            actual-llm-call-id
                            event)

          (< 1 (count matching-responses))
          {:type ambiguous-action-response-event-type
           :terminal true
           :invariant-violated true
           :llm-call-id actual-llm-call-id
           :event event})
        next-state
        (if violation
          state
          (update state :actions dissoc
                  (ffirst matching-responses)))]
    [next-state (continue-monitoring next-state violation)]))

(defn- advance-correlation
  "Advance the correlation obligation addressed by EVENT."
  [{:keys [llm-responses actions] :as state} event]
  (let [event-type (e/type event)
        pending-llm-responses
        (llm-responses-addressed-by llm-responses event-type)
        pending-action-requests
        (actions-addressed-by actions :request event-type)
        pending-action-responses
        (actions-addressed-by actions :response event-type)]
    (cond
      (nil? event-type)
      (start-monitoring)

      (= call-llm-event-type event-type)
      (remember-llm-call state event)

      (seq pending-llm-responses)
      (remember-response-actions state event pending-llm-responses)

      (seq pending-action-requests)
      (await-action-response state event pending-action-requests)

      (seq pending-action-responses)
      (complete-action state event pending-action-responses)

      :else
      [state (continue-monitoring state)])))

(defn make-bthread
  "Create a safety bthread that traces LLM call IDs through responses and actions."
  []
  (b/step advance-correlation))
