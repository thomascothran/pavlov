(ns tech.thomascothran.pavlov.model.check-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.model.check :as check]
            [tech.thomascothran.pavlov.bthread :as b]))

(deftest good-morning-evening-model-check
  (testing "Model checker should verify good-morning/good-evening alternation"
    (let [result
          (check/check
           {:bthreads
            {:good-morning
             (b/repeat 4 {:request #{:good-morning}})

             :good-evening
             (b/repeat 4 {:request #{:good-evening}})

             :interlace
             (b/round-robin
              [{:wait-on #{:good-morning}
                :block #{:good-evening}}
               {:wait-on #{:good-evening}
                :block #{:good-morning}}])}})]

      ;; Should find no violations - the bthreads correctly alternate
      (is (nil? result)
          "Should find no violations with proper alternation"))))

(deftest good-morning-evening-deadlock
  (testing "Model checker should detect deadlock when both are blocked"
    (let [result
          (check/check
           {:bthreads {:good-morning
                       (b/bids [{:request #{:good-morning}}])

                       :good-evening
                       (b/bids [{:request #{:good-evening}}])

                       :block-all
                       (b/bids [{:block #{:good-morning :good-evening}}])}
            :check-deadlock? true})]

      ;; Should find a deadlock
      (is (some? result)
          "Should detect deadlock")

      (when result
        (is (= :deadlock (:type result))
            "Violation type should be :deadlock")))))

(deftest deadlock-after-first-event
  (testing "Model checker should detect deadlock after first event"
    (let [result
          (check/check
           {:bthreads {:requester
                       (b/bids [{:request #{:first-event}}])

                       :blocker
                       (b/bids [{:wait-on #{:first-event}}
                                {:block #{:second-event}}])

                       :second-requester
                       (b/bids [{:wait-on #{:first-event}}
                                {:request #{:second-event}}])}
            :check-deadlock? true})]

      ;; Should find a deadlock after :first-event
      (is (some? result)
          "Should detect deadlock after first event")

      (when result
        (is (= :deadlock (:type result))
            "Violation type should be :deadlock")

        (is (= [:first-event] (:path result))
            "Path should show :first-event happened before deadlock")))))

(deftest simple-invariant-violation
  (testing "Model checker should detect invariant violations"
    (let [result (check/check
                  {:bthreads
                   {:violator
                    (b/bids [{:request #{{:type :violation
                                          :invariant-violated true}}}])}})]

      ;; Should find the violation immediately
      (is (some? result)
          "Should detect violation")

      (when result
        (is (= :safety-violation (:type result))
            "Violation type should be :safety-violation")

        (is (= :violation (:type (:event result)))
            "The violating event should have type :violation")))))

(deftest branching-exploration-required-test
  (testing "Model checker should explore all branches when multiple events could be selected"
    (let [;; Create a scenario where different paths lead to different outcomes
          ;; If model checker explores all branches, it should find the violation
          result
          (check/check
           {:bthreads {:racer-a
                       (b/bids [{:request #{:event-b :event-a}}])

                       ;; This bthread creates different outcomes based on order
                       :conditional-violator
                       (b/bids [{:wait-on #{:event-a}}
                                ;; If event-a happens first, request a violation
                                {:request #{{:type :violation
                                             :invariant-violated true}}}])}
            :check-deadlock? false})]

      (is result
          "Should find a violation (but only because it takes the :event-a path)")

      (when result
        (is (= :safety-violation (:type result)))
        (is (= [:event-a] (:path result))
            "Path should show only :event-a was explored")))))

(deftest directly-check-branching
  (testing "Model checker should explore all branches when multiple events could be selected"
    (let [;; Create a scenario where different paths lead to different outcomes
          ;; If model checker explores all branches, it should find the violation
          !events (atom [])
          _result
          (check/check
           {:bthreads {:top-level-events
                       (b/bids [{:request #{:event-b :event-a}}])

                       :a-mid-level-events
                       (b/bids [{:wait-on #{:event-a}}
                                {:request #{:event-a1}}])

                       :b-mid-level-events
                       (b/bids [{:wait-on #{:event-b}}
                                {:request #{:event-b1}}])

                       :a-terminate
                       (b/bids [{:wait-on #{:event-a1}}
                                {:request #{{:type :a-is-done
                                             :terminal true}}}])

                       :b1-low-level-events
                       (b/bids [{:wait-on #{:event-b1}}
                                {:request #{:event-b1i :event-b1ii
                                            :event-b1iii}}])

                       :b1i-terminal-event
                       (b/bids [{:wait-on #{:event-b1i}}
                                {:request #{{:type :bi1-is-done
                                             :terminal true}}}])
                       :b1ii-low-level-events
                       (b/bids [{:wait-on #{:event-b1ii}}
                                {:request #{:event-b1iiA}}])

                       :b1iii-low-level-events
                       (b/bids [{:wait-on #{:event-b1iii}}
                                {:request #{:event-b1iiiA :event-b1iiiB}}])

                       :watcher-bthread
                       (b/step (fn [_prev-state event]
                                 (swap! !events conj event)
                                 [nil {:wait-on #{:event-a :event-a1
                                                  :event-b :event-b1 :event-b1i
                                                  :event-b1ii :event-b1iiA
                                                  :event-b1iii :event-b1iiiA :event-b1iiiB}}]))}

            :check-deadlock? false})]
      (is (= #{nil :event-a :event-b :event-a1 :event-b1 :event-b1i
               :event-b1ii :event-b1iiA
               :event-b1iii :event-b1iiiA :event-b1iiiB}
             (into #{} @!events))))))
