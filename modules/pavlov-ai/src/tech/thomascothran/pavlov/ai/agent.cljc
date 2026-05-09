(ns tech.thomascothran.pavlov.ai.agent
  "Pure LLM agent bthreads.

  The agent bthread never calls an LLM or executes tools directly. It emits
  request events that name request-specific success/failure event types.
  Runtime bthreads or subscribers perform side effects and answer with events
  of those types.

  Example: email assistant

  ```clojure
  (require '[tech.thomascothran.pavlov.ai.agent :as ai-agent])

  (def email-agent
    (ai-agent/make-agent-bthread
     {:id :email-assistant
      :system \"You help triage email, draft concise replies, and avoid sending anything without approval.\"
      :initial-tools [{:tool-name :email/search
                       :description \"Search recent email messages\"
                       :input-schema {:type \"object\"
                                      :properties {:query {:type \"string\"}}}}
                      {:tool-name :email/draft-reply
                       :description \"Create an email reply draft\"
                       :input-schema {:type \"object\"
                                      :properties {:message-id {:type \"string\"}
                                                   :body {:type \"string\"}}}}]}))

  ;; Invoke with the addressed event type derived from `:id`:
  {:type [:pavlov.ai.agent/invoke :email-assistant]
   :session-id :pi-session/123
   :conversation-id :email-triage/today
   :message {:role :user
             :content \"Find urgent emails from this morning and draft replies.\"}}
  ```

  Example: code update assistant

  ```clojure
  (def code-agent
    (ai-agent/make-agent-bthread
     {:id :code-assistant
      :system \"You help update Clojure code. Prefer small patches and explain test results.\"
      :initial-tools [{:tool-name :fs/read
                       :description \"Read a project file\"
                       :input-schema {:type \"object\"
                                      :properties {:path {:type \"string\"}}}}
                      {:tool-name :fs/edit
                       :description \"Apply a precise file edit\"
                       :input-schema {:type \"object\"
                                      :properties {:path {:type \"string\"}
                                                   :old-text {:type \"string\"}
                                                   :new-text {:type \"string\"}}}}
                      {:tool-name :clojure/test
                       :description \"Run Clojure tests\"
                       :input-schema {:type \"object\"
                                      :properties {:focus {:type \"string\"}}}}]}))

  {:type [:pavlov.ai.agent/invoke :code-assistant]
   :session-id :pi-session/123
   :conversation-id :code-change/fix-agent-docstring
   :message {:role :user
             :content \"Update the agent docstring and run the focused tests.\"}}
  ```"
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as event]))

(def default-invoke-event-type
  :pavlov.ai.agent/invoke)

(def default-response-event-type
  :pavlov.ai.agent/responded)

(def default-failed-event-type
  :pavlov.ai.agent/failed)

(def default-cancel-event-type
  :pavlov.ai.agent/cancel)

(def default-llm-call-event-type
  :pavlov.ai.llm/call)

(def default-llm-success-event-type
  :pavlov.ai.llm/response-received)

(def default-llm-failure-event-type
  :pavlov.ai.llm/response-failed)

(def default-tool-registered-event-type
  :pavlov.ai.tool/registered)

(def default-tool-deregistered-event-type
  :pavlov.ai.tool/deregistered)

(def default-skill-registered-event-type
  :pavlov.ai.skill/registered)

(def default-skill-deregistered-event-type
  :pavlov.ai.skill/deregistered)

(defn- event-type
  [event]
  (event/type event))

(defn- addressed-event-type
  [base-event-type agent-id]
  [base-event-type agent-id])

(defn- event-types
  [{:keys [id
           invoke-event-type
           response-event-type
           failed-event-type
           cancel-event-type
           llm-call-event-type
           llm-success-event-type
           llm-failure-event-type
           tool-registered-event-type
           tool-deregistered-event-type
           skill-registered-event-type
           skill-deregistered-event-type]}]
  {:invoke (or invoke-event-type
               (addressed-event-type default-invoke-event-type id))
   :response (or response-event-type
                 (addressed-event-type default-response-event-type id))
   :failed (or failed-event-type
               (addressed-event-type default-failed-event-type id))
   :cancel (or cancel-event-type
               (addressed-event-type default-cancel-event-type id))
   :llm-call (or llm-call-event-type
                 (addressed-event-type default-llm-call-event-type id))
   :llm-success (or llm-success-event-type
                    (addressed-event-type default-llm-success-event-type id))
   :llm-failure (or llm-failure-event-type
                    (addressed-event-type default-llm-failure-event-type id))
   :tool-registered (or tool-registered-event-type
                        (addressed-event-type default-tool-registered-event-type id))
   :tool-deregistered (or tool-deregistered-event-type
                          (addressed-event-type default-tool-deregistered-event-type id))
   :skill-registered (or skill-registered-event-type
                         (addressed-event-type default-skill-registered-event-type id))
   :skill-deregistered (or skill-deregistered-event-type
                           (addressed-event-type default-skill-deregistered-event-type id))})

(defn- base-wait-on
  [{:keys [event-types]}]
  (into #{}
        (keep event-types)
        [:invoke
         :cancel
         :tool-registered
         :tool-deregistered
         :skill-registered
         :skill-deregistered]))

(defn- initial-state
  [{:keys [id system initial-tools initial-skills] :as opts}]
  (let [event-types (event-types opts)]
    {:phase :idle
     :id id
     :system system
     :event-types event-types
     :messages []
     :tools (into {} (map (juxt :tool-name identity)) initial-tools)
     :skills (into {} (map (juxt :skill-id identity)) initial-skills)
     :call-seq 0
     :pending-llm nil}))

(defn- conversation-id
  [event]
  (:conversation-id event))

(defn- next-call-id
  [{:keys [id call-seq]} invocation]
  (or (:llm-call-id invocation)
      (:call-id invocation)
      [id (conversation-id invocation) (inc call-seq)]))

(defn- invocation-messages
  [invocation]
  (cond
    (:messages invocation) (:messages invocation)
    (:message invocation) [(:message invocation)]
    (:content invocation) [{:role :user :content (:content invocation)}]
    :else []))

(defn- llm-tools
  [state]
  (->> (:tools state)
       vals
       (sort-by (comp str :tool-name))
       vec))

(defn- wait-on-for-state
  [{:keys [phase pending-llm] :as state}]
  (cond-> (base-wait-on state)
    (= :awaiting-llm phase)
    (into (filter identity)
          [(:success-event-type pending-llm)
           (:failure-event-type pending-llm)])))

(defn- bid-for-state
  [state]
  {:wait-on (wait-on-for-state state)})

(defn- register-tool
  [state event]
  (if (:tool-name event)
    (assoc-in state [:tools (:tool-name event)] (dissoc event :type))
    state))

(defn- deregister-tool
  [state event]
  (if (:tool-name event)
    (update state :tools dissoc (:tool-name event))
    state))

(defn- register-skill
  [state event]
  (if (:skill-id event)
    (assoc-in state [:skills (:skill-id event)] (dissoc event :type))
    state))

(defn- deregister-skill
  [state event]
  (if (:skill-id event)
    (update state :skills dissoc (:skill-id event))
    state))

(defn- invoke-agent
  [state invocation]
  (let [conversation-id' (conversation-id invocation)
        call-id (next-call-id state invocation)
        success-event-type (get-in state [:event-types :llm-success])
        failure-event-type (get-in state [:event-types :llm-failure])
        messages (into (:messages state) (invocation-messages invocation))
        call-request {:type (get-in state [:event-types :llm-call])
                      :agent-id (:id state)
                      :conversation-id conversation-id'
                      :call-id call-id
                      :system (:system state)
                      :messages messages
                      :tools (llm-tools state)
                      :success-event-type success-event-type
                      :failure-event-type failure-event-type}
        state' (-> state
                   (assoc :phase :awaiting-llm
                          :messages messages
                          :pending-llm {:conversation-id conversation-id'
                                        :call-id call-id
                                        :success-event-type success-event-type
                                        :failure-event-type failure-event-type})
                   (update :call-seq inc))]
    [state' (assoc (bid-for-state state') :request #{call-request})]))

(defn- pending-response?
  [state event success-or-failure]
  (let [expected-event-type (get-in state [:pending-llm success-or-failure])]
    (and expected-event-type
         (= expected-event-type (event-type event))
         (= (get-in state [:pending-llm :conversation-id])
            (conversation-id event))
         (= (get-in state [:pending-llm :call-id])
            (:call-id event)))))

(defn- complete-llm-success
  [state event]
  (let [message (get-in event [:response :message])
        messages (cond-> (:messages state)
                   message (conj message))
        response-event {:type (get-in state [:event-types :response])
                        :agent-id (:id state)
                        :conversation-id (conversation-id event)
                        :call-id (:call-id event)
                        :message message
                        :response (:response event)}
        state' (assoc state
                      :phase :idle
                      :messages messages
                      :pending-llm nil)]
    [state' (assoc (bid-for-state state') :request #{response-event})]))

(defn- complete-llm-failure
  [state event]
  (let [failed-event {:type (get-in state [:event-types :failed])
                      :agent-id (:id state)
                      :conversation-id (conversation-id event)
                      :call-id (:call-id event)
                      :error (:error event)}
        state' (assoc state
                      :phase :idle
                      :pending-llm nil)]
    [state' (assoc (bid-for-state state') :request #{failed-event})]))

(defn make-agent-bthread
  "Create a pure LLM agent bthread.

  Required options:
  - `:id` - agent identifier used to derive addressed event types
  - `:system` - system prompt included in LLM call requests

  Optional options:
  - `:initial-tools` - collection of tool registration maps keyed by `:tool-name`
  - `:initial-skills` - collection of skill registration maps keyed by `:skill-id`
  - `:invoke-event-type` - event type for agent invocation
  - `:response-event-type` - event type requested when the agent has a final response
  - `:failed-event-type` - event type requested when the LLM call fails
  - `:cancel-event-type` - event type reserved for cancellation
  - `:llm-call-event-type` - event type requested to ask a runtime to call the LLM
  - `:llm-success-event-type` - event type the runtime should use for LLM success
  - `:llm-failure-event-type` - event type the runtime should use for LLM failure
  - `:tool-registered-event-type` - event type for tool registration
  - `:tool-deregistered-event-type` - event type for tool deregistration
  - `:skill-registered-event-type` - event type for skill registration
  - `:skill-deregistered-event-type` - event type for skill deregistration

  Event type options default to addressed vector event types derived from `:id`,
  for example `[:pavlov.ai.agent/invoke :assistant]`.

  The bthread listens for addressed invocation, tool registration, skill
  registration, and cancellation events. On invocation it requests an LLM call
  event that includes `:success-event-type` and `:failure-event-type` for the
  runtime bridge to answer with."
  [opts]
  (b/step
   (fn [state incoming-event]
     (let [state (or state (initial-state opts))
           event-types (:event-types state)
           type' (event-type incoming-event)]
       (cond
         (nil? incoming-event)
         [state (bid-for-state state)]

         (= type' (:tool-registered event-types))
         (let [state' (register-tool state incoming-event)]
           [state' (bid-for-state state')])

         (= type' (:tool-deregistered event-types))
         (let [state' (deregister-tool state incoming-event)]
           [state' (bid-for-state state')])

         (= type' (:skill-registered event-types))
         (let [state' (register-skill state incoming-event)]
           [state' (bid-for-state state')])

         (= type' (:skill-deregistered event-types))
         (let [state' (deregister-skill state incoming-event)]
           [state' (bid-for-state state')])

         (and (= type' (:invoke event-types))
              (= :idle (:phase state)))
         (invoke-agent state incoming-event)

         (and (= :awaiting-llm (:phase state))
              (pending-response? state incoming-event :success-event-type))
         (complete-llm-success state incoming-event)

         (and (= :awaiting-llm (:phase state))
              (pending-response? state incoming-event :failure-event-type))
         (complete-llm-failure state incoming-event)

         :else
         [state (bid-for-state state)])))))

(def make-llm-agent-bthread
  "Alias for `make-agent-bthread`."
  make-agent-bthread)
