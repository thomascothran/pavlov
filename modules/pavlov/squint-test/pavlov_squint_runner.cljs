(ns pavlov-squint-runner
  (:require [cljs.test :as test]
            [tech.thomascothran.pavlov.bthread-test]
            [tech.thomascothran.pavlov.bprogram.ephemeral-test :as ephemeral-test]
            [tech.thomascothran.pavlov.bprogram.notification-test]
            [tech.thomascothran.pavlov.bprogram.state-test]
            [tech.thomascothran.pavlov.event.selection.prioritized-bids-test]
            [tech.thomascothran.pavlov.event.selection.prioritized-events-test]))

(defn main
  []
  ;; These two lifecycle tests use primitive event types and are supported by
  ;; the initial Squint profile. The remaining ephemeral tests use vectors as
  ;; set members/event types and stay deferred until Pavlov supplies structural
  ;; collection semantics over Squint's native mutable collections.
  (-> (test/run-tests
       ephemeral-test/good-morning-and-evening
       ephemeral-test/add-subscriber)
      js/Promise.resolve
      (.then
       (fn [ephemeral-summary]
         (-> (test/run-tests
              'tech.thomascothran.pavlov.bthread-test
              'tech.thomascothran.pavlov.bprogram.notification-test
              'tech.thomascothran.pavlov.bprogram.state-test
              'tech.thomascothran.pavlov.event.selection.prioritized-bids-test
              'tech.thomascothran.pavlov.event.selection.prioritized-events-test)
             js/Promise.resolve
             (.then
              (fn [core-summary]
                (when-not (and (test/successful? ephemeral-summary)
                               (test/successful? core-summary))
                  (set! (.-exitCode js/process) 1)))))))))

(-> (main)
    (.catch
     (fn [error]
       (js/console.error error)
       (set! (.-exitCode js/process) 1))))
