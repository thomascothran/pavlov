(ns tech.thomascothran.pavlov.ai.agent.model-check
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.model.check :as check]
            [tech.thomascothran.pavlov.ai.agent.scenarios
             :as scenarios]
            [tech.thomascothran.pavlov.ai.agent.environment :as env]
            [tech.thomascothran.pavlov.ai.event :as ae]
            [tech.thomascothran.pavlov.ai.agent.safety :as safety]
            [tech.thomascothran.pavlov.bthread]))

;; Scenarios

(defn make-bthreads
  []
  (into (scenarios/make-bthreads)
        {::fanout-agent-events
         (ae/fan-out-agent-events)}))

;; =====

(deftest check-agent
  (let [bthreads (make-bthreads)
        violations
        (check/check {:bthreads bthreads
                      :safety-bthreads (safety/make-bthreads)
                      :possible (into #{:email/list
                                        :email/send
                                        :text-response
                                        ::safety/multiple-actions-safely-rejected}
                                      (scenarios/possible-events))
                      :check-deadlock? false
                      :environment-bthreads (env/make-bthreads)})]
    (def violations violations)
    (when violations
      (tap> [::violations violations]))
    (is (not (boolean violations)))))
