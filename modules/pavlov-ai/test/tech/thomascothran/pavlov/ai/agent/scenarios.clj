(ns tech.thomascothran.pavlov.ai.agent.scenarios
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.ai.agent :as agent]))

(defn make-minimal-happy-path
  []
  (let [happy-path-config
        {:name :happy-path
         :initialized-event ::happy-path-initialized
         :invocation-event ::invoke-happy-path
         :response-event-type ::happy-path-response-event}

        happy-path-agent (agent/make-bthread happy-path-config)

        hello-world-message
        {:role "user" :content "hello world"}]
    (b/bids [{:bthreads {::happy-path-agent happy-path-agent}
              :wait-on #{::happy-path-initialized}
              :hot true}

             {:request #{{:type ::invoke-happy-path
                          :message hello-world-message}}

              :hot true}

             {:wait-on #{::happy-path-response-event}
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
