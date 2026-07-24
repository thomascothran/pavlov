(ns tech.thomascothran.pavlov.ai.agent
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.ai.schema :as schema]
            [tech.thomascothran.pavlov.ai.event
             :refer [agent-invocation-event-type
                     make-initialized-event
                     make-action-rejected-event
                     make-agent-response
                     llm-response-event-type
                     action-rejected-event-type
                     call-llm-event-type
                     make-action-response-type]]))

(defn make-llm-event
  [agent-config llm-response-event-type
   {:keys [message-history llm-calls]
    :or {message-history []
         llm-calls 0}
    :as _state}
   message]
  (assert (map? agent-config))
  (let [agent-name (get agent-config :name)
        actions (get agent-config :actions)
        llm-calls' (inc llm-calls)]
    {:type call-llm-event-type
     :agent-name agent-name
     :actions actions
     :llm-call-id [agent-name llm-calls']
     :llm-response-event-type llm-response-event-type
     :llm-calls llm-calls'
     :messages (conj message-history message)}))

(defn llm-invocation->
  "Produce the bid that invokes the LLM"
  [agent-config state event llm-response-event-type default-waits]
  (let [{:keys [messages llm-calls] :as llm-event}
        (make-llm-event agent-config llm-response-event-type state (:message event))
        state (assoc state
                     :message-history messages
                     :llm-calls llm-calls)
        bid {:wait-on default-waits
             :request #{llm-event}}]
    [state bid]))

(defn action-result->
  "Produce the bid from the action result"
  [agent-config state {:keys [result llm-call-id action-type] :as event}
   llm-response-event-type default-waits]
  (let [message {:content result
                 :llm-call-id llm-call-id
                 :action-type action-type
                 :role "user"}
        {:keys [messages llm-calls] :as llm-event}
        (make-llm-event agent-config llm-response-event-type state message)
        state (assoc state
                     :message-history messages
                     :llm-calls llm-calls)
        bid {:wait-on default-waits
             :request #{llm-event}}]
    [state bid]))

(defn- action-arguments
  [action]
  (dissoc action :type))

(defn- validate-action
  [action-specs {:keys [type] :as action}]
  (if-not (contains? action-specs type)
    {:action action
     :action-type type
     :reason :undeclared-action}
    (let [request-schema (get-in action-specs [type :request/schema])
          arguments (action-arguments action)]
      (try
        (when-not (schema/validate request-schema arguments)
          {:action action
           :action-type type
           :reason :invalid-arguments
           :explanation (schema/explain request-schema arguments)})
        (catch #?(:clj Exception :cljs :default) error
          {:action action
           :action-type type
           :reason :schema-validation-error
           :message (ex-message error)
           :data (ex-data error)})))))

(defn- action-validation-errors
  [action-specs actions]
  (cond-> (into []
                (keep #(validate-action action-specs %))
                actions)
    (> (count actions) 1)
    (conj {:reason :multiple-actions-not-supported
           :action-count (count actions)
           :actions actions})))

(defn- action-rejected-message
  [event]
  {:role "user"
   :llm-call-id (:llm-call-id event)
   :content {:kind :action-rejected
             :violations (:violations event)}})

(defn- validate-config
  [{:keys [actions] :as config}]
  (doseq [[action-type action-spec] actions]
    (when-not (:request/schema action-spec)
      (throw (ex-info "Agent action must have a request schema"
                      {:action-type action-type
                       :action-spec action-spec}))))
  config)

(defn make-bthread
  [config]
  (assert (:name config)
          "Agent must have a name")
  (validate-config config)
  (let [agent-name (:name config)
        invocation-event [agent-invocation-event-type agent-name]
        llm-response-event-type [llm-response-event-type agent-name]
        ;; Assumes that we only have one tool call outstanding at a time
        action-response-type
        (make-action-response-type agent-name)
        action-rejected-response-type [action-rejected-event-type agent-name]
        default-waits #{invocation-event
                        llm-response-event-type
                        action-response-type
                        action-rejected-response-type}]

    (b/step
     (fn [state event]
       (let [event-type (event/type event)]
         (cond
           (nil? event-type)
           [config
            {:request #{(make-initialized-event config)}
             :wait-on default-waits}]

           ;; invoke llm
           (= event-type invocation-event)
           (llm-invocation-> config state event llm-response-event-type default-waits)

           ;; llm responses
           (= event-type llm-response-event-type)
           (let [{:keys [response llm-call-id]} event
                 state (update state :message-history conj
                               {:content response
                                :role "assistant"
                                :llm-call-id llm-call-id})
                 errors (action-validation-errors
                         (:actions config)
                         (:actions response))]
             (if (seq errors)
               [state
                {:request #{(make-action-rejected-event
                             agent-name
                             llm-call-id
                             errors)}
                 :wait-on default-waits}]
               [state
                {:request (make-agent-response
                           (constantly action-response-type)
                           event)}]))

           ;; rejected actions are returned to the LLM as feedback
           (= event-type action-rejected-response-type)
           (llm-invocation-> config
                             state
                             {:message (action-rejected-message event)}
                             llm-response-event-type
                             default-waits)

           ;; action responses
           (= event-type action-response-type)
           (action-result-> config state event llm-response-event-type default-waits)

           :else
           [state {:wait-on default-waits}]))))))
