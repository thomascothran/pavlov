(ns tech.thomascothran.pavlov.ai.agent.scenarios
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.ai.agent :as agent]
            [tech.thomascothran.pavlov.ai.schema.malli]
            [tech.thomascothran.pavlov.ai.schema :as ais]
            [tech.thomascothran.pavlov.ai.event
             :refer [make-invocation-event
                     call-llm-event-type
                     action-rejected-event-type]]))

(def list-email-action
  {:description "List recent emails"
   :request/schema [:map
                    [:lookback
                     [:map
                      [:unit [:enum :hours :minutes]]
                      [:value :int]]]]
   :success/type :email/fetch-list-suceeded})

(comment
  (-> list-email-action :request/schema ais/->json-schema))

(def send-email-action
  {:description "Send an email"
   :request/schema [:map
                    [:subject :string]
                    [:message :string]]
   :success/type :email/send-succeeded})

(def text-response-action
  {:description "Respond to the user with text. Yields control back to the bprogram."
   :request/schema [:map [:response :string]]
   :success/type :text-response})

(def happy-path-config
  {:name :happy-path
   :actions {:email/list list-email-action
             :email/send send-email-action
             :text-response text-response-action}})

(def hello-world-message
  {:role "user" :content "hello world"})

(def invocation-event
  (make-invocation-event :happy-path
                         {:message hello-world-message}))

(defn make-minimal-happy-path
  "Ensures that we go from invocation to calling the LLM to returning a response
  to the bhread"
  []
  (let [happy-path-agent (agent/make-bthread happy-path-config)]

    (b/bids [{:bthreads {::happy-path-agent happy-path-agent}
              :wait-on #{[:pavlov.ai/agent-bthread-initialized :happy-path]}
              :hot true}

             {:request #{invocation-event}
              :hot true}

             {:wait-on #{call-llm-event-type}
              :hot true}

             (fn [{:keys [llm-response-event-type]}] ;; done via test env
               {:wait-on #{llm-response-event-type}  ;; and real llm bthread outside
                :hot true})                          ;; tests

             (fn [{:keys [response] :as event}]
               (if response
                 {:request #{::successful-happy-path}}
                 {:request #{{:type ::unsuccessful-happy-path
                              :event event
                              :invariant-violated true}}}))])))

(defn make-tool-call-path
  []
  (b/bids [{:wait-on #{:email/list}}
           {:wait-on #{[:pavlov.ai/llm-response :happy-path]}}
           {:wait-on #{[:pavlov.ai/llm-response :happy-path]}}
           {:wait-on #{[:pavlov.ai/llm-response :happy-path]}}
           {:request #{{:type :! :invariant-violated true}}}]))

(defn- rejection-has?
  [event action-type reason]
  (some #(and (= action-type (:action-type %))
              (= reason (:reason %)))
        (:violations event)))

(defn- wait-for-addressed-rejection
  [action-type reason {:keys [type agent-name] :as event}]
  (if (rejection-has? event action-type reason)
    {:wait-on #{[type agent-name]}}
    {:wait-on #{::unrelated-action-rejection}}))

(defn- llm-called-with-rejection
  [event]
  (let [message (last (:messages event))
        content (:content message)
        undeclared-action?
        (some #(and (= :non-existent-action (:action-type %))
                    (= :undeclared-action (:reason %)))
              (:violations content))]
    (if (and (= "user" (:role message))
             (= :action-rejected (:kind content))
             undeclared-action?)
      {:request #{::invalid-action-returned-to-llm}}
      {:request #{{:type ::invalid-action-not-returned-to-llm
                   :invariant-violated true
                   :expected {:kind :action-rejected
                              :action-type :non-existent-action
                              :reason :undeclared-action}
                   :llm-call event}}})))

(defn- llm-called-with-invalid-arguments-rejection
  [event]
  (let [message (last (:messages event))
        content (:content message)
        invalid-arguments?
        (some #(and (= :email/list (:action-type %))
                    (= :invalid-arguments (:reason %))
                    (some? (:explanation %)))
              (:violations content))]
    (if (and (= "user" (:role message))
             (= :action-rejected (:kind content))
             invalid-arguments?)
      {:request #{::invalid-action-arguments-returned-to-llm}}
      {:request #{{:type ::invalid-action-arguments-not-returned-to-llm
                   :invariant-violated true
                   :expected {:kind :action-rejected
                              :action-type :email/list
                              :reason :invalid-arguments
                              :explanation :present}
                   :llm-call event}}})))

(defn make-invalid-action-rejection-path
  []
  (b/bids
   [{:wait-on #{action-rejected-event-type}}

    (partial wait-for-addressed-rejection
             :non-existent-action
             :undeclared-action)

    {:wait-on #{call-llm-event-type}}

    llm-called-with-rejection]))

(defn make-invalid-action-arguments-rejection-path
  []
  (b/bids
   [{:wait-on #{action-rejected-event-type}}

    (partial wait-for-addressed-rejection
             :email/list
             :invalid-arguments)

    {:wait-on #{call-llm-event-type}}

    llm-called-with-invalid-arguments-rejection]))

(defn make-bthreads
  []
  {::minimal-happy-path (make-minimal-happy-path)
   ::invalid-action-rejection-path (make-invalid-action-rejection-path)
   ::invalid-action-arguments-rejection-path
   (make-invalid-action-arguments-rejection-path)
   :tool-call-path (make-tool-call-path)})

(defn possible-events
  []
  #{::successful-happy-path
    ::invalid-action-returned-to-llm
    ::invalid-action-arguments-returned-to-llm})
