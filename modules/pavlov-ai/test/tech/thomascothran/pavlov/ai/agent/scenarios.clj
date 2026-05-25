(ns tech.thomascothran.pavlov.ai.agent.scenarios
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.ai.agent :as agent]
            [tech.thomascothran.pavlov.ai.event
             :refer [make-invocation-event
                     agent-response-event-type]]))

(defn make-minimal-happy-path
  []
  (let [happy-path-config
        {:name :happy-path
         :response-event-type ::happy-path-response-event}

        happy-path-agent (agent/make-bthread happy-path-config)

        hello-world-message
        {:role "user" :content "hello world"}

        invocation-event
        (make-invocation-event :happy-path
                               {:message hello-world-message})]

    (b/bids [{:bthreads {::happy-path-agent happy-path-agent}
              :wait-on #{[:pavlov.ai/agent-bthread-initialized :happy-path]}
              :hot true}

             {:request #{invocation-event}
              :hot true}

             {:wait-on #{[agent-response-event-type
                          :happy-path]}
              :hot true}

             (fn [{:keys [response] :as event}]
               (if response
                 {:request #{::successful-happy-path}}
                 {:request #{{:type ::unsuccessful-happy-path
                              :event event
                              :invariant-violated true}}}))])))

(defn make-bthreads
  []
  {::minimal-happy-path (make-minimal-happy-path)})
