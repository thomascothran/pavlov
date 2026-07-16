(ns tech.thomascothran.pavlov.ai.agent.safety
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]))

(def ^:private
  missing-actions
  {:type ::missing-actions
   :terminal true
   :invariant-violated true})

(def ^:private missing-messages
  {:type ::missing-message-history
   :terminal true
   :invariant-violated true})

(def ^:private missing-roles
  {:type ::missing-message-roles
   :terminal true
   :invariant-violated true})

(def ^:private missing-content
  {:type ::missing-message-content
   :terminal true
   :invariant-violated true})

(def ^:private non-existent-action-requested
  {:type ::non-existent-action-requested
   :terminal true
   :invariant-violated true})

(def ^:private invalid-email-list-arguments-requested
  {:type ::invalid-email-list-arguments-requested
   :terminal true
   :invariant-violated true})

(def ^:private multiple-action-response-forwarded
  {:type ::multiple-action-response-forwarded
   :terminal true
   :invariant-violated true})

(def ^:private overlapping-llm-call
  {:type ::overlapping-llm-call
   :terminal true
   :invariant-violated true})

(defn make-invalid-email-list-arguments-bthread
  []
  (b/on :email/list
        (fn [event]
          (when (= "twenty" (get-in event [:lookback :value]))
            {:request #{(assoc invalid-email-list-arguments-requested
                               :event event)}}))))

(defn make-non-existent-action-bthread
  []
  (b/on :non-existent-action
        (fn [event]
          {:request #{(assoc non-existent-action-requested
                             :event event)}})))

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
                {:request #{(assoc missing-content
                                   :event event)}}))))

(defn make-llm-id-tracing-bthread
  "Ensures that llm ids are properly passed through"
  []
  (b/step
   (fn [{:keys [wait-on event->call-id]
         :as state}
        event]
     (let [event-type (e/type event)]
       (cond
         (nil? event-type) ;; setup
         [{:wait-on #{:pavlov.ai/call-llm}}
          {:wait-on #{:pavlov.ai/call-llm}}]

         (= :pavlov.ai/call-llm event-type)
         (let [{:keys [llm-response-event-type
                       llm-call-id]} event

               _ (assert llm-response-event-type)
               _ (assert llm-call-id)
               next-state
               (-> state
                   (assoc-in [:event->call-id
                              llm-response-event-type]
                             llm-call-id)
                   (update :wait-on conj llm-response-event-type))

               bid
               {:wait-on (conj wait-on llm-response-event-type)}]
           [next-state bid])

         ;; we have another event - the response
         (get event->call-id event-type)
         (let [expected-llm-call-id
               (get-in state [:event->call-id event-type])

               actual-llm-call-id (get event :llm-call-id)

               request
               (when (not= expected-llm-call-id
                           actual-llm-call-id)
                 {:type ::mismatched-call-id
                  :terminal true
                  :invariant-violated true
                  :actual-llm-call-id actual-llm-call-id
                  :expected-llm-call-id expected-llm-call-id
                  :event event})

               response-event-type (:response-event-type event)

               ;; extract events and actions from
               ;; llm response events
               action-events->llm-call-id
               (into (if response-event-type
                       {response-event-type actual-llm-call-id}
                       {})
                     (comp (map e/type)
                           (map #(vector % actual-llm-call-id)))
                     (get-in event [:response :actions]))

               new-waits
               (-> wait-on
                   (disj event-type)
                   (into (keys action-events->llm-call-id)))

               new-state
               (-> state
                   (update :event->call-id dissoc event-type)
                   (update :event->call-id merge action-events->llm-call-id)
                   (assoc :wait-on new-waits))]

           [new-state
            (cond-> {:wait-on new-waits}
              request (assoc :request #{request}))])

         :else
         [state {:wait-on wait-on}])))))

(defn make-ensure-call-ids-on-llm-calls-bthread
  []
  (let [default-bid {:wait-on #{:pavlov.ai/call-llm}}

        id-check-request-request
        (fn id-check-bid
          [state event agent-name]
          (let [mismatch-call-count
                (not= (-> state
                          (get-in [:call-count agent-name] 0)
                          inc)
                      (:llm-calls event)
                      (get-in event [:llm-call-id 1]))

                wrong-agent-name
                (not= agent-name
                      (get-in event [:llm-call-id 0]))
                base {:type ::call-id-check-failed
                      :terminal true
                      :invariant-violated true
                      :state state
                      :event event}]
            (cond mismatch-call-count
                  (assoc base :reason :mismatched-call-count)

                  wrong-agent-name
                  (assoc base :reason :wrong-agent-name))))]
    (b/step
     (fn [state event]
       (let [event-type (e/type event)
             agent-name (when (map? event)
                          (get event :agent-name))

             state-call-count
             (get-in state [:call-count agent-name] 0)]

         (cond (nil? event)
               [state default-bid]

               (= :pavlov.ai/call-llm event-type)
               [(assoc-in state
                          [:call-count agent-name]
                          (inc state-call-count))
                (if-let [request (id-check-request-request state event agent-name)]
                  (assoc default-bid :request #{request})
                  default-bid)]))))))

(defn multiple-action-response?
  [event]
  (and (= [:pavlov.ai/llm-response :happy-path] (:type event))
       (> (count (get-in event [:response :actions])) 1)))

(defn forwarded-multiple-action?
  [event]
  (or (= {:unit :minutes :value 21} (:lookback event))
      (= "Multiple action response" (:subject event))))

(defn make-multiple-action-response-not-forwarded-bthread
  []
  (b/bids
   [{:wait-on #{[:pavlov.ai/llm-response :happy-path]}}

    (fn [event]
      (if (multiple-action-response? event)
        {:wait-on #{:email/list
                    :email/send
                    :text-response
                    [:pavlov.ai/action-rejected :happy-path]}
         :hot nil #_true}
        {:wait-on #{::unrelated-llm-response}}))

    (fn [event]
      (if (forwarded-multiple-action? event)
        {:request #{(assoc multiple-action-response-forwarded
                           :event event)}}
        {:request #{::multiple-actions-safely-rejected}}))]))

(defn make-single-llm-call-safety-bthread
  []
  (b/bids
   [{:wait-on #{:pavlov.ai/call-llm}}

    {:wait-on #{:pavlov.ai/call-llm
                [:pavlov.ai/llm-response :happy-path]}}

    (fn [event]
      (if (= :pavlov.ai/call-llm (:type event))
        {:request #{(assoc overlapping-llm-call
                           :event event)}}
        {:wait-on #{::single-llm-call-observed}}))]))

(defn make-bthreads
  []
  {::make-invalid-email-list-arguments-bthread
   (make-invalid-email-list-arguments-bthread)
   ::make-non-existent-action-bthread (make-non-existent-action-bthread)
   ::make-action-spec-bthread (make-action-spec-bthread)
   ::make-history-check-bthread (make-history-check-bthread)
   ::make-multiple-action-response-not-forwarded-bthread
   (make-multiple-action-response-not-forwarded-bthread)
   ::make-single-llm-call-safety-bthread
   (make-single-llm-call-safety-bthread)
   ::ensure-call-ids (make-ensure-call-ids-on-llm-calls-bthread)
   ::llm-id-tracing-bthread (make-llm-id-tracing-bthread)})
