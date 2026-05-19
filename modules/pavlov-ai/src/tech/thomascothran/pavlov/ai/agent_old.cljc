(ns tech.thomascothran.pavlov.ai.agent-old
  "Pure LLM agent bthreads.

  The agent bthread never calls an LLM or executes tools directly. It emits
  request events that name request-specific success/failure event types.
  Runtime bthreads or subscribers perform side effects and answer with events
  of those types.

  Agents may also expose themselves as tools by supplying a `:tool` map.
  Tool-exposed agents listen for their tool invocation event, turn structured
  `:arguments` into an LLM request, require structured output using the tool
  `:output-schema`, and route completion to the caller-provided tool
  success/failure event types. When `:tool` is present, both
  `:tool.input-schema` and `:tool.output-schema` are required.

  LLM tool calls are handled as a normal Pavlov effect loop. If an LLM response
  contains `:tool-calls`, the agent requests the matching registered tool
  invocation events and waits for tool success/failure events. When all pending
  tool calls have succeeded, their results are appended as tool messages and the
  agent requests the next LLM call. An LLM response without tool calls is treated
  as the final agent/tool result.

  Skills follow Pi-style progressive disclosure. Registered skills are summaries
  that must include `:skill-id` and `:description`; full skill loading can be
  modeled as ordinary tools. Invalid runtime skill registrations are ignored,
  and invalid `:initial-skills` entries fail agent construction.

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
                                      :properties {:query {:type \"string\"}}
                                      :required [\"query\"]}
                       :output-schema {:type \"object\"
                                       :properties {:messages {:type \"array\"}}
                                       :required [\"messages\"]}}
                      {:tool-name :email/draft-reply
                       :description \"Create an email reply draft\"
                       :input-schema {:type \"object\"
                                      :properties {:message-id {:type \"string\"}
                                                   :body {:type \"string\"}}
                                      :required [\"message-id\" \"body\"]}
                       :output-schema {:type \"object\"
                                       :properties {:draft-id {:type \"string\"}
                                                    :preview {:type \"string\"}}
                                       :required [\"draft-id\" \"preview\"]}}]
      :initial-skills [{:skill-id :email-triage
                        :description \"Use for prioritizing urgent emails and drafting concise replies.\"}]}))

  ;; Invoke with the addressed event type derived from `:id`:
  {:type [:pavlov.ai.agent/invoke :email-assistant]
   :session-id :pi-session/123
   :conversation-id :email-triage/today
   :message {:role :user
             :content \"Find urgent emails from this morning and draft replies.\"}}
  ```

  Example: code update assistant exposed as a tool

  ```clojure
  (def code-agent
    (ai-agent/make-agent-bthread
     {:id :code-assistant
      :system \"You help update Clojure code. Prefer small patches and explain test results.\"
      :tool {:name :delegate/code
             :description \"Delegate coding tasks to the code assistant.\"
             :input-schema {:type \"object\"
                            :properties {:task {:type \"string\"}
                                         :constraints {:type \"array\"
                                                       :items {:type \"string\"}}}
                            :required [\"task\"]}
             :output-schema {:type \"object\"
                             :properties {:status {:type \"string\"
                                                   :enum [\"completed\" \"failed\" \"needs-info\"]}
                                          :summary {:type \"string\"}}
                             :required [\"status\" \"summary\"]}}
      :initial-tools [{:tool-name :fs/read
                       :description \"Read a project file\"
                       :input-schema {:type \"object\"
                                      :properties {:path {:type \"string\"}}
                                      :required [\"path\"]}
                       :output-schema {:type \"object\"
                                       :properties {:content {:type \"string\"}}
                                       :required [\"content\"]}}
                      {:tool-name :fs/edit
                       :description \"Apply a precise file edit\"
                       :input-schema {:type \"object\"
                                      :properties {:path {:type \"string\"}
                                                   :old-text {:type \"string\"}
                                                   :new-text {:type \"string\"}}
                                      :required [\"path\" \"old-text\" \"new-text\"]}
                       :output-schema {:type \"object\"
                                       :properties {:changed? {:type \"boolean\"}}
                                       :required [\"changed?\"]}}
                      {:tool-name :clojure/test
                       :description \"Run Clojure tests\"
                       :input-schema {:type \"object\"
                                      :properties {:focus {:type \"string\"}}}
                       :output-schema {:type \"object\"
                                       :properties {:passed? {:type \"boolean\"}
                                                    :summary {:type \"string\"}}
                                       :required [\"passed?\" \"summary\"]}}]
      :initial-skills [{:skill-id :clojure-debugging
                        :description \"Use for debugging Clojure code, tests, macros, and REPL issues.\"}]}))

  ;; Direct invocation, e.g. from a UI/workflow/user-facing entry point:
  {:type [:pavlov.ai.agent/invoke :code-assistant]
   :session-id :pi-session/123
   :conversation-id :code-change/fix-agent-docstring
   :message {:role :user
             :content \"Update the agent docstring and run the focused tests.\"}}

  ;; Tool invocation, e.g. delegated from another agent's LLM tool loop:
  {:type [:pavlov.ai.tool/invocation :delegate/code]
   :conversation-id :code-change/fix-agent-docstring
   :tool-call-id \"call_123\"
   :tool-name :delegate/code
   :caller-agent-id :planner
   :arguments {:task \"Update the agent docstring and run focused tests.\"}
   :success-event-type [:pavlov.ai.tool/succeeded :planner]
   :failure-event-type [:pavlov.ai.tool/failed :planner]}
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

(def default-tool-invocation-event-type
  :pavlov.ai.tool/invocation)

(def default-tool-success-event-type
  :pavlov.ai.tool/succeeded)

(def default-tool-failure-event-type
  :pavlov.ai.tool/failed)

(def default-tool-deregistered-event-type
  :pavlov.ai.tool/deregistered)

(def default-max-tool-rounds
  20)

(def default-skill-registered-event-type
  :pavlov.ai.skill/registered)

(def default-skill-deregistered-event-type
  :pavlov.ai.skill/deregistered)

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
           tool-invocation-event-type
           tool-success-event-type
           tool-failure-event-type
           skill-registered-event-type
           skill-deregistered-event-type]
    :as opts}]
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
   :tool-invocation (or tool-invocation-event-type
                        (when-let [tool (:tool opts)]
                          (let [tool-name (or (:name tool) (:tool-name tool) id)]
                            (or (:invocation-event-type tool)
                                (addressed-event-type default-tool-invocation-event-type tool-name)))))
   :tool-success (or tool-success-event-type
                     (addressed-event-type default-tool-success-event-type id))
   :tool-failure (or tool-failure-event-type
                     (addressed-event-type default-tool-failure-event-type id))
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
         :skill-deregistered
         :tool-invocation]))

(defn- normalize-agent-tool
  [id tool]
  (when tool
    (let [tool-name (or (:name tool) (:tool-name tool) id)]
      (assoc tool
             :name tool-name
             :tool-name tool-name
             :invocation-event-type (or (:invocation-event-type tool)
                                        (addressed-event-type default-tool-invocation-event-type tool-name))))))

(defn- initial-state
  [{:keys [id system initial-tools initial-skills llm-opts max-tool-rounds on-complete] :as opts}]
  (let [event-types (event-types opts)]
    {:phase :idle
     :id id
     :system system
     :llm-opts llm-opts
     :event-types event-types
     :agent-tool (normalize-agent-tool id (:tool opts))
     :on-complete (or on-complete :close)
     :messages []
     :tools (into {} (map (juxt :tool-name identity)) initial-tools)
     :skills (into {} (map (juxt :skill-id identity)) initial-skills)
     :call-seq 0
     :max-tool-rounds (or max-tool-rounds default-max-tool-rounds)
     :tool-rounds 0
     :pending-llm nil
     :pending-invocation nil
     :pending-tools nil}))

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

(defn- tool-invocation-messages
  [tool invocation]
  (if-let [arguments->messages (:arguments->messages tool)]
    (arguments->messages (:arguments invocation))
    [{:role :user
      :content (pr-str (:arguments invocation))}]))

(defn- llm-tools
  [state]
  (->> (:tools state)
       vals
       (sort-by (comp str :tool-name))
       vec))

(defn- skill-summaries
  [state]
  (->> (:skills state)
       vals
       (map #(select-keys % [:skill-id :description]))
       (sort-by (comp str :skill-id))
       vec))

(defn- system-with-skills
  [system skills]
  (if (seq skills)
    (str system
         "\n\nAvailable skills:\n"
         (apply str
                (map (fn [{:keys [skill-id description]}]
                       (str "- " skill-id " - " description "\n"))
                     skills)))
    system))

(defn- add-skills-to-llm-request
  [request state]
  (let [skills (skill-summaries state)]
    (cond-> (assoc request :system (system-with-skills (:system request) skills))
      (seq skills) (assoc :skills skills))))

(defn- merged-llm-opts
  [state invocation]
  (merge (:llm-opts state) (:llm-opts invocation)))

(defn- tool-call-id
  [tool-call]
  (or (:id tool-call)
      (:tool-call-id tool-call)))

(defn- tool-call-name
  [tool-call]
  (or (:name tool-call)
      (:tool-name tool-call)))

(defn- response-tool-calls
  [event]
  (get-in event [:response :message :tool-calls]))

(defn- tool-invocation-event-type
  [tool tool-name]
  (or (:invocation-event-type tool)
      (addressed-event-type default-tool-invocation-event-type tool-name)))

(defn- wait-on-for-state
  [{:keys [phase pending-llm] :as state}]
  (cond-> (base-wait-on state)
    (= :awaiting-llm phase)
    (into (filter identity)
          [(:success-event-type pending-llm)
           (:failure-event-type pending-llm)])

    (= :awaiting-tools phase)
    (into (filter identity)
          [(get-in state [:event-types :tool-success])
           (get-in state [:event-types :tool-failure])])))

(defn- bid-for-state
  [state]
  {:wait-on (wait-on-for-state state)})

(defn- complete-with-request
  [state response-event]
  (case (:on-complete state)
    :close
    (let [state' (assoc state :phase :closing)]
      [state' {:request #{response-event}}])

    :idle
    [state (assoc (bid-for-state state) :request #{response-event})]))

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

(defn- valid-skill-registration?
  [skill]
  (and (:skill-id skill)
       (:description skill)))

(defn- register-skill
  [state event]
  (if (valid-skill-registration? event)
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
        llm-opts (merged-llm-opts state invocation)
        call-request (add-skills-to-llm-request
                      (cond-> {:type (get-in state [:event-types :llm-call])
                               :agent-id (:id state)
                               :conversation-id conversation-id'
                               :call-id call-id
                               :system (:system state)
                               :messages messages
                               :tools (llm-tools state)
                               :success-event-type success-event-type
                               :failure-event-type failure-event-type}
                        llm-opts (assoc :llm-opts llm-opts))
                      state)
        state' (-> state
                   (assoc :phase :awaiting-llm
                          :messages messages
                          :pending-llm {:origin :agent
                                        :conversation-id conversation-id'
                                        :call-id call-id
                                        :success-event-type success-event-type
                                        :failure-event-type failure-event-type
                                        :llm-opts llm-opts})
                   (update :call-seq inc))]
    [state' (assoc (bid-for-state state') :request #{call-request})]))

(defn- next-llm-call-request
  [state conversation-id' call-id messages pending-invocation]
  (add-skills-to-llm-request
   (cond-> {:type (get-in state [:event-types :llm-call])
            :agent-id (:id state)
            :conversation-id conversation-id'
            :call-id call-id
            :system (:system state)
            :messages messages
            :tools (llm-tools state)
            :success-event-type (get-in state [:event-types :llm-success])
            :failure-event-type (get-in state [:event-types :llm-failure])}
     (:llm-opts pending-invocation)
     (assoc :llm-opts (:llm-opts pending-invocation))
     (= :tool (:origin pending-invocation))
     (assoc :output-schema (get-in state [:agent-tool :output-schema])))
   state))

(defn- invoke-agent-tool
  [state invocation]
  (let [tool (:agent-tool state)
        conversation-id' (conversation-id invocation)
        call-id (next-call-id state invocation)
        success-event-type (get-in state [:event-types :llm-success])
        failure-event-type (get-in state [:event-types :llm-failure])
        messages (into (:messages state) (tool-invocation-messages tool invocation))
        llm-opts (merged-llm-opts state invocation)
        call-request (add-skills-to-llm-request
                      (cond-> {:type (get-in state [:event-types :llm-call])
                               :agent-id (:id state)
                               :conversation-id conversation-id'
                               :call-id call-id
                               :system (:system state)
                               :messages messages
                               :tools (llm-tools state)
                               :success-event-type success-event-type
                               :failure-event-type failure-event-type}
                        (:output-schema tool)
                        (assoc :output-schema (:output-schema tool))
                        llm-opts
                        (assoc :llm-opts llm-opts))
                      state)
        state' (-> state
                   (assoc :phase :awaiting-llm
                          :messages messages
                          :pending-llm {:origin :tool
                                        :conversation-id conversation-id'
                                        :call-id call-id
                                        :success-event-type success-event-type
                                        :failure-event-type failure-event-type
                                        :tool-name (:tool-name tool)
                                        :tool-call-id (:tool-call-id invocation)
                                        :llm-opts llm-opts
                                        :tool-success-event-type (:success-event-type invocation)
                                        :tool-failure-event-type (:failure-event-type invocation)})
                   (update :call-seq inc))]
    [state' (assoc (bid-for-state state') :request #{call-request})]))

(defn- begin-tool-round
  [state event]
  (let [tool-calls (vec (response-tool-calls event))
        message (get-in event [:response :message])
        messages (cond-> (:messages state)
                   message (conj message))
        success-event-type (get-in state [:event-types :tool-success])
        failure-event-type (get-in state [:event-types :tool-failure])
        pending-invocation (:pending-llm state)
        tool-requests (->> tool-calls
                           (map (fn [tool-call]
                                  (let [tool-name (tool-call-name tool-call)
                                        tool (get-in state [:tools tool-name])]
                                    {:type (tool-invocation-event-type tool tool-name)
                                     :agent-id (:id state)
                                     :conversation-id (conversation-id event)
                                     :tool-name tool-name
                                     :tool-call-id (tool-call-id tool-call)
                                     :arguments (:arguments tool-call)
                                     :success-event-type success-event-type
                                     :failure-event-type failure-event-type})))
                           set)
        pending-tools (into {}
                            (map-indexed (fn [idx tool-call]
                                           [(tool-call-id tool-call)
                                            {:tool-name (tool-call-name tool-call)
                                             :arguments (:arguments tool-call)
                                             :index idx}]))
                            tool-calls)
        state' (assoc state
                      :phase :awaiting-tools
                      :messages messages
                      :pending-llm nil
                      :pending-invocation pending-invocation
                      :pending-tools pending-tools
                      :tool-rounds (inc (:tool-rounds state)))]
    [state' (assoc (bid-for-state state') :request tool-requests)]))

(defn- tool-result-message
  [event]
  {:role :tool
   :tool-call-id (:tool-call-id event)
   :name (:tool-name event)
   :content (pr-str (:result event))})

(defn- tool-error-message
  [event]
  {:role :tool
   :tool-call-id (:tool-call-id event)
   :name (:tool-name event)
   :content (pr-str {:error (:error event)})})

(defn- pending-tool-response?
  [state event success-or-failure]
  (let [expected-event-type (get-in state [:event-types success-or-failure])]
    (and (= :awaiting-tools (:phase state))
         (= expected-event-type (event/type event))
         (= (get-in state [:pending-invocation :conversation-id])
            (conversation-id event))
         (contains? (:pending-tools state) (:tool-call-id event)))))

(defn- complete-tool-result
  [state event message]
  (let [pending-tools (assoc-in (:pending-tools state)
                                [(:tool-call-id event) :message]
                                message)
        pending-invocation (:pending-invocation state)]
    (if-not (every? :message (vals pending-tools))
      (let [state' (assoc state :pending-tools pending-tools)]
        [state' (bid-for-state state')])
      (let [messages (into (:messages state)
                           (map :message)
                           (sort-by :index (vals pending-tools)))
            call-id [(:id state)
                     (:conversation-id pending-invocation)
                     (inc (:call-seq state))]
            pending-llm (assoc pending-invocation
                               :call-id call-id
                               :success-event-type (get-in state [:event-types :llm-success])
                               :failure-event-type (get-in state [:event-types :llm-failure]))
            call-request (next-llm-call-request state
                                                (:conversation-id pending-invocation)
                                                call-id
                                                messages
                                                pending-llm)
            state' (-> state
                       (assoc :phase :awaiting-llm
                              :messages messages
                              :pending-tools nil
                              :pending-invocation nil
                              :pending-llm pending-llm)
                       (update :call-seq inc))]
        [state' (assoc (bid-for-state state') :request #{call-request})]))))

(defn- complete-tool-success
  [state event]
  (complete-tool-result state event (tool-result-message event)))

(defn- complete-tool-failure
  [state event]
  (complete-tool-result state event (tool-error-message event)))

(defn- pending-response?
  [state event success-or-failure]
  (let [expected-event-type (get-in state [:pending-llm success-or-failure])]
    (and expected-event-type
         (= expected-event-type (event/type event))
         (= (get-in state [:pending-llm :conversation-id])
            (conversation-id event))
         (= (get-in state [:pending-llm :call-id])
            (:call-id event)))))

(defn- tool-round-limit-message
  [state]
  {:role :user
   :content (str "The tool call limit of " (:max-tool-rounds state)
                 " tool rounds has been reached. Do not call any more tools; return a final answer now using the information already available.")})

(defn- request-final-answer-after-tool-limit
  [state event]
  (let [pending-invocation (:pending-llm state)
        assistant-message (get-in event [:response :message])
        messages (cond-> (:messages state)
                   assistant-message (conj assistant-message)
                   true (conj (tool-round-limit-message state)))
        call-id [(:id state)
                 (:conversation-id pending-invocation)
                 (inc (:call-seq state))]
        pending-llm (assoc pending-invocation
                           :call-id call-id
                           :success-event-type (get-in state [:event-types :llm-success])
                           :failure-event-type (get-in state [:event-types :llm-failure]))
        call-request (next-llm-call-request state
                                            (:conversation-id pending-invocation)
                                            call-id
                                            messages
                                            pending-llm)
        state' (-> state
                   (assoc :phase :awaiting-llm
                          :messages messages
                          :pending-llm pending-llm)
                   (update :call-seq inc))]
    [state' (assoc (bid-for-state state') :request #{call-request})]))

(defn- complete-llm-success
  [state event]
  (if (seq (response-tool-calls event))
    (if (>= (:tool-rounds state) (:max-tool-rounds state))
      (request-final-answer-after-tool-limit state event)
      (begin-tool-round state event))
    (let [pending (:pending-llm state)
          message (get-in event [:response :message])
          messages (cond-> (:messages state)
                     message (conj message))
          response-event (if (= :tool (:origin pending))
                           {:type (:tool-success-event-type pending)
                            :agent-id (:id state)
                            :conversation-id (conversation-id event)
                            :call-id (:call-id event)
                            :tool-name (:tool-name pending)
                            :tool-call-id (:tool-call-id pending)
                            :result (or (get-in event [:response :output])
                                        (get-in event [:response :structured-output])
                                        (get-in event [:response :message :content]))}
                           {:type (get-in state [:event-types :response])
                            :agent-id (:id state)
                            :conversation-id (conversation-id event)
                            :call-id (:call-id event)
                            :message message
                            :response (:response event)})
          state' (assoc state
                        :phase :idle
                        :messages messages
                        :pending-llm nil)]
      (complete-with-request state' response-event))))

(defn- complete-llm-failure
  [state event]
  (let [pending (:pending-llm state)
        failed-event (if (= :tool (:origin pending))
                       {:type (:tool-failure-event-type pending)
                        :agent-id (:id state)
                        :conversation-id (conversation-id event)
                        :call-id (:call-id event)
                        :tool-name (:tool-name pending)
                        :tool-call-id (:tool-call-id pending)
                        :error (:error event)}
                       {:type (get-in state [:event-types :failed])
                        :agent-id (:id state)
                        :conversation-id (conversation-id event)
                        :call-id (:call-id event)
                        :error (:error event)})
        state' (assoc state
                      :phase :idle
                      :pending-llm nil)]
    (complete-with-request state' failed-event)))

(defn- validate-options
  [opts]
  (when (and (:on-complete opts)
             (not (#{:close :idle} (:on-complete opts))))
    (throw (ex-info "Agent :on-complete must be either :close or :idle"
                    {:reason :invalid-on-complete
                     :agent-id (:id opts)
                     :on-complete (:on-complete opts)})))
  (doseq [skill (:initial-skills opts)]
    (when-not (:skill-id skill)
      (throw (ex-info "Agent :initial-skills entries require :skill-id"
                      {:reason :missing-initial-skill-id
                       :agent-id (:id opts)
                       :skill skill})))
    (when-not (:description skill)
      (throw (ex-info "Agent :initial-skills entries require :description"
                      {:reason :missing-initial-skill-description
                       :agent-id (:id opts)
                       :skill-id (:skill-id skill)
                       :skill skill}))))
  (when-let [tool (:tool opts)]
    (when-not (:input-schema tool)
      (throw (ex-info "Agent :tool.input-schema is required when :tool is present"
                      {:reason :missing-tool-input-schema
                       :agent-id (:id opts)})))
    (when-not (:output-schema tool)
      (throw (ex-info "Agent :tool.output-schema is required when :tool is present"
                      {:reason :missing-tool-output-schema
                       :agent-id (:id opts)}))))
  opts)

(defn make-agent-bthread
  "Create a pure LLM agent bthread.

  Required options:
  - `:id` - agent identifier used to derive addressed event types
  - `:system` - system prompt included in LLM call requests

  Optional options:
  - `:initial-tools` - collection of tool registration maps keyed by `:tool-name`.
    Tools are exposed to the LLM; if the LLM returns `:tool-calls`, the agent
    requests the matching tool invocation events and waits for tool results.
  - `:initial-skills` - collection of skill summary maps keyed by `:skill-id`.
    Each entry must include `:skill-id` and `:description`.
  - `:tool` - expose this agent as a tool. When present, must include
    `:input-schema` and `:output-schema`; may include `:name`, `:description`,
    `:invocation-event-type`, and `:arguments->messages`
  - `:invoke-event-type` - event type for agent invocation
  - `:response-event-type` - event type requested when the agent has a final response
  - `:failed-event-type` - event type requested when the LLM call fails
  - `:cancel-event-type` - event type reserved for cancellation
  - `:llm-call-event-type` - event type requested to ask a runtime to call the LLM
  - `:llm-success-event-type` - event type the runtime should use for LLM success
  - `:llm-failure-event-type` - event type the runtime should use for LLM failure
  - `:tool-registered-event-type` - event type for tool registration
  - `:tool-deregistered-event-type` - event type for tool deregistration
  - `:tool-success-event-type` - event type used by registered tools to report success
  - `:tool-failure-event-type` - event type used by registered tools to report failure
  - `:skill-registered-event-type` - event type for skill registration. Skill
    registrations must include `:skill-id` and `:description`; invalid
    registrations are ignored.
  - `:skill-deregistered-event-type` - event type for skill deregistration
  - `:on-complete` - lifecycle behavior after emitting a final response/failure:
    `:idle` keeps the bthread alive with accumulated messages; `:close` keeps
    the final response/failure requested and terminates when that event is selected.
    Defaults to `:close`.

  Event type options default to addressed vector event types derived from `:id`,
  for example `[:pavlov.ai.agent/invoke :assistant]`.

  The bthread listens for addressed invocation, tool registration, skill
  registration, cancellation events, and, when `:tool` is present, the tool
  invocation event. Agent invocations request normal agent responses. Tool
  invocations request LLM output with the tool `:output-schema` and route the
  final result to the caller's tool success/failure event type.

  LLM responses with `:tool-calls` are not final responses. They move the agent
  to `:awaiting-tools`, request tool invocation events using each registered
  tool's `:invocation-event-type` when present, append successful tool results
  as `{:role :tool ...}` messages, and then request another LLM call. LLM
  responses without tool calls complete the current agent or tool invocation."
  [opts]
  (let [opts (validate-options opts)]
    (b/step
     (fn [state incoming-event]
       (let [state (or state (initial-state opts))
             event-types (:event-types state)
             type' (event/type incoming-event)]
         (cond
           (= :closing (:phase state))
           nil

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

           (and (= type' (:tool-invocation event-types))
                (= :idle (:phase state)))
           (invoke-agent-tool state incoming-event)

           (and (= :awaiting-llm (:phase state))
                (pending-response? state incoming-event :success-event-type))
           (complete-llm-success state incoming-event)

           (and (= :awaiting-llm (:phase state))
                (pending-response? state incoming-event :failure-event-type))
           (complete-llm-failure state incoming-event)

           (pending-tool-response? state incoming-event :tool-success)
           (complete-tool-success state incoming-event)

           (pending-tool-response? state incoming-event :tool-failure)
           (complete-tool-failure state incoming-event)

           :else
           [state (bid-for-state state)]))))))
