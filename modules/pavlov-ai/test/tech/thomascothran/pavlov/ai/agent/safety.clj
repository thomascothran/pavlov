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

(def ^:private non-existent-action-requested
  {:type ::non-existent-action-requested
   :invariant-violated true})

(def ^:private invalid-email-list-arguments-requested
  {:type ::invalid-email-list-arguments-requested
   :invariant-violated true})

(def ^:private multiple-action-response-forwarded
  {:type ::multiple-action-response-forwarded
   :invariant-violated true})

(def ^:private overlapping-llm-call
  {:type ::overlapping-llm-call
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
   (make-single-llm-call-safety-bthread)})
