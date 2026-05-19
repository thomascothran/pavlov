(ns tech.thomascothran.pavlov.ai.agent.model-check
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.model.check :as check]
            [tech.thomascothran.pavlov.ai.agent.scenarios
             :as scenarios]
            [tech.thomascothran.pavlov.ai.agent.environment :as env]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
            [tech.thomascothran.pavlov.bthread :as b]))

;; Scenarios

(defn make-safety-bthreads
  []
  {})

(defn make-liveness-bthreads
  []
  {})

(defn make-agent-bthreads
  []
  {})

;; =====

(deftest check-agent
  (let [bthreads (scenarios/make-bthreads)
        violations
        (check/check {:bthreads bthreads
                      :check-deadlock? false
                      :environment-bthreads (env/make-bthreads)})]
    (def violations violations)
    (when violations
      (tap> [::violations violations]))
    (is (not (boolean violations)))))
