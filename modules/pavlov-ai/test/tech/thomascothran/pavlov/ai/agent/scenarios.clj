(ns tech.thomascothran.pavlov.ai.agent.scenarios
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.ai.agent :as agent]
            [tech.thomascothran.pavlov.ai.schema.malli]
            [tech.thomascothran.pavlov.ai.schema :as ais]
            [tech.thomascothran.pavlov.ai.event
             :refer [make-invocation-event
                     agent-response-event-type]]))

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

(def text-response-action
  {:description "Respond to the user with text. Yields control back to the bprogram."
   :request/schema [:map [:response :string]]
   :success/type :text-response})

(def happy-path-config
  {:name :happy-path
   :response-event-type ::happy-path-response-event
   :actions {:email/list list-email-action
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

             {:wait-on #{:pavlov.ai/call-llm}
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
           #_{:request #{{:type :! :invariant-violated true}}}]))

(defn make-bthreads
  []
  {::minimal-happy-path (make-minimal-happy-path)
   :tool-call-path (make-tool-call-path)})

(defn possible-events
  []
  #{::successful-happy-path})
