(ns tech.thomascothran.pavlov.ai.agent.safety.llm-id-tracing
  "Safety monitoring for correlation IDs across LLM calls and their actions.

  The monitor keeps LLM responses separate from action lifecycles:

  - `:llm-responses` contains `[response-event-type llm-call-id]` pairs.
  - `:actions` is keyed by `[action-type llm-call-id]` and records whether
    each action is awaiting its request event or its response event.
  - `:rejections` contains `[rejection-event-type llm-call-id]` alternatives
    to action requests.
  - `:assistant-history` and `:rejection-history` record messages that must
    appear, with their call IDs, in the next observable history for that agent."
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]))

(def ^:private call-llm-event-type :pavlov.ai/call-llm)

(def ^:private mismatched-call-id-event-type
  :tech.thomascothran.pavlov.ai.agent.safety/mismatched-call-id)

(def ^:private ambiguous-action-response-event-type
  :tech.thomascothran.pavlov.ai.agent.safety/ambiguous-action-response)

(def ^:private missing-assistant-history-event-type
  :tech.thomascothran.pavlov.ai.agent.safety/missing-assistant-history)

(def ^:private missing-rejection-history-event-type
  :tech.thomascothran.pavlov.ai.agent.safety/missing-rejection-history)

(def ^:private action-rejected-event-type :pavlov.ai/action-rejected)

(defn- pending-event-types
  "Return the event types that can complete a pending LLM or action correlation."
  [{:keys [llm-responses actions rejections]}]
  (into #{call-llm-event-type}
        (concat
         (map first llm-responses)
         (map first rejections)
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
               :actions {}
               :rejections #{}
               :assistant-history {}
               :rejection-history {}}]
    [state (continue-monitoring state)]))

(defn- assistant-message-recorded?
  "Return true when message history contains the expected assistant response."
  [messages llm-call-id response]
  (some #(and (= "assistant" (:role %))
              (= llm-call-id (:llm-call-id %))
              (= response (:content %)))
        messages))

(defn- missing-assistant-history
  "Return assistant responses absent from the next history emitted by AGENT-NAME."
  [assistant-history agent-name messages]
  (into {}
        (remove (fn [[llm-call-id response]]
                  (or (not= agent-name (first llm-call-id))
                      (assistant-message-recorded? messages
                                                   llm-call-id
                                                   response))))
        assistant-history))

(defn- forget-assistant-history
  "Remove verified assistant-history obligations for AGENT-NAME."
  [state agent-name]
  (update state :assistant-history
          (fn [assistant-history]
            (into {}
                  (remove (fn [[llm-call-id _response]]
                            (= agent-name (first llm-call-id))))
                  assistant-history))))

(defn- rejection-message-recorded?
  "Return true when message history contains the expected rejection feedback."
  [messages llm-call-id content]
  (some #(and (= "user" (:role %))
              (= llm-call-id (:llm-call-id %))
              (= content (:content %)))
        messages))

(defn- missing-rejection-history
  "Return rejection feedback absent from the next history emitted by AGENT-NAME."
  [rejection-history agent-name messages]
  (into {}
        (remove (fn [[llm-call-id content]]
                  (or (not= agent-name (first llm-call-id))
                      (rejection-message-recorded? messages
                                                   llm-call-id
                                                   content))))
        rejection-history))

(defn- forget-rejection-history
  "Remove verified rejection-history obligations for AGENT-NAME."
  [state agent-name]
  (update state :rejection-history
          (fn [rejection-history]
            (into {}
                  (remove (fn [[llm-call-id _content]]
                            (= agent-name (first llm-call-id))))
                  rejection-history))))

(defn- remember-llm-call
  "Verify prior correlated history and remember the response expected for this call."
  [state {:keys [agent-name llm-response-event-type llm-call-id messages]
          :as event}]
  (assert llm-response-event-type)
  (assert llm-call-id)
  (let [missing-assistant (missing-assistant-history (:assistant-history state)
                                                     agent-name
                                                     messages)
        missing-rejection (missing-rejection-history (:rejection-history state)
                                                     agent-name
                                                     messages)
        violation
        (cond
          (seq missing-assistant)
          {:type missing-assistant-history-event-type
           :terminal true
           :invariant-violated true
           :agent-name agent-name
           :missing-assistant-history missing-assistant
           :event event}

          (seq missing-rejection)
          {:type missing-rejection-history-event-type
           :terminal true
           :invariant-violated true
           :agent-name agent-name
           :missing-rejection-history missing-rejection
           :event event})
        next-state (if violation
                     state
                     (-> state
                         (forget-assistant-history agent-name)
                         (forget-rejection-history agent-name)
                         (update :llm-responses
                                 conj
                                 [llm-response-event-type llm-call-id])))]
    [next-state (continue-monitoring next-state violation)]))

(defn- remember-response-actions
  "Replace a completed LLM response correlation with its expected actions."
  [state event pending-response-keys]
  (let [event-type (e/type event)
        actual-llm-call-id (:llm-call-id event)
        rejection-event-type [action-rejected-event-type (:agent-name event)]
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
              (update :actions merge action-entries)
              (update :rejections conj
                      [rejection-event-type actual-llm-call-id])
              (assoc-in [:assistant-history actual-llm-call-id]
                        (:response event))))]
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
          (-> state
              (assoc-in [:actions action-key]
                        {:stage :response
                         :response-event-type (:response-event-type event)})
              (update :rejections
                      (fn [rejections]
                        (into #{}
                              (remove (fn [[_rejection-type llm-call-id]]
                                        (= actual-llm-call-id llm-call-id)))
                              rejections)))))]
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

(defn- rejections-addressed-by
  "Return pending rejection alternatives addressed by the selected event type."
  [rejections event-type]
  (filter #(= event-type (first %)) rejections))

(defn- complete-rejection
  "Complete a rejected-action alternative and remember its feedback history."
  [state event pending-rejections]
  (let [event-type (e/type event)
        actual-llm-call-id (:llm-call-id event)
        expected-llm-call-ids (into #{} (map second) pending-rejections)
        mismatch (when-not (expected-llm-call-ids actual-llm-call-id)
                   (call-id-mismatch expected-llm-call-ids
                                     actual-llm-call-id
                                     event))
        rejection-content {:kind :action-rejected
                           :violations (:violations event)}
        next-state
        (if mismatch
          state
          (-> state
              (update :rejections disj
                      [event-type actual-llm-call-id])
              (update :actions
                      (fn [actions]
                        (into {}
                              (remove (fn [[[_action-type llm-call-id]
                                            _action-state]]
                                        (= actual-llm-call-id llm-call-id)))
                              actions)))
              (assoc-in [:rejection-history actual-llm-call-id]
                        rejection-content)))]
    [next-state (continue-monitoring next-state mismatch)]))

(defn- advance-correlation
  "Advance the correlation obligation addressed by EVENT."
  [{:keys [llm-responses actions rejections] :as state} event]
  (let [event-type (e/type event)
        pending-llm-responses
        (llm-responses-addressed-by llm-responses event-type)
        pending-rejections
        (rejections-addressed-by rejections event-type)
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

      (seq pending-rejections)
      (complete-rejection state event pending-rejections)

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
