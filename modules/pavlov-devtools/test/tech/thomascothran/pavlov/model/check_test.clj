(ns tech.thomascothran.pavlov.model.check-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.model.check :as check]
            [tech.thomascothran.pavlov.bthread :as b]))

(deftest good-morning-evening-model-check
  (testing "Model checker should verify good-morning/good-evening alternation"
    (let [result
          (check/check
           {:check-deadlock? false
            :bthreads
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

(deftest simple-deadlock
  (let [result (check/check
                {:bthreads
                 [[:test (b/bids [{:request #{{:type :a}}}
                                  {:request #{{:type :b}}}])]]
                 :check-deadlock? true})]
    (is result)))

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

(deftest deadlock-not-triggered-on-terminal-event
  ;; Catches the case where the state of a bthread does not change
  ;; and the bids are the same. In order to identify a unique bprogram
  ;; you *also* need the event. Otherwise different bprograms in the
  ;; same state can be identified as dupes
  (let [bthreads {:request-a
                  (b/bids [{:request [{:type :a}]}])

                  :request-b
                  (b/on :a (constantly {:request #{{:type :b}
                                                   {:type :c}}}))

                  :request-c
                  (b/on :b (constantly {:request #{{:type :d
                                                    :terminal true}}}))}
        result (check/check {:bthreads bthreads
                             :check-deadlock? true})]
    (is result)))

(deftest error-thrown-in-step-fn
  (let [bthreads {:throw
                  (b/step (fn [state event]
                            (throw (ex-info "boom" {:state state
                                                    :event event}))))
                  :throw-after-init
                  (b/step (fn [state event]
                            (if event
                              (throw (ex-info "boom" {:state state
                                                      :event event}))
                              {:request #{{:type :init-done}}})))}
        result (check/check {:bthreads bthreads
                             :check-deadlock? true})]
    (is result)))

(defn make-update-sync-bthreads
  []
  [[:request-cms-sync-bthreads
    (b/bids
     [{:request #{{:type ::sync-thing-from-cms}}}])]

   [::terminate-on-not-found
    (b/on ::thing-not-found-in-cms
          (constantly {:request #{{:type ::terminate-on-not-found
                                   :terminal true}}}))]
   [::terminate-on-unknown-error
    (b/on ::unknown-error-fetching-thing-from-cms
          (constantly {:request #{{:type ::terminate-on-unknown-error
                                   :terminal true}}}))]

   [::fetch-from-cms
    (b/on ::sync-thing-from-cms
          (fn [_]
            {:request #{{:type ::thing-fetched-from-cms
                         :a {:test :data}}
                        {:type ::thing-not-found-in-cms}
                        {:type ::unknown-error-fetching-thing-from-cms
                         :cms-response {:status 404}}}}))]

   [::update-thing
    (b/on ::thing-fetched-from-cms
          (fn [_]
            {:request #{{:type ::update-thing}}}))]])

(deftest test-complex-identifier-bug
  ;; Catches another case where the state of a bthread does not change
  ;; and the bids are the same. In order to identify a unique bprogram
  ;; you *also* need the event. Otherwise different bprograms in the
  ;; same state can be identified as dupes
  (let [violation (check/check {:bthreads (make-update-sync-bthreads)
                                :check-deadlock? true})]
    (is violation)))

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

(deftest no-livelock-when-program-terminates
  (testing "Program that terminates normally should not be flagged as livelock"
    (let [result (check/check
                  {:bthreads {:terminate
                              (b/bids [{:request #{{:type :done :terminal true}}}])}
                   :check-livelock? true})]
      (is (nil? result)
          "Should find no violations when program terminates normally"))))

(deftest simplest-livelock-detected
  (testing "Infinite loop with no escape should be detected as livelock"
    (let [result (check/check
                  {:bthreads {:ping-pong
                              (b/round-robin [{:request #{:ping}}
                                              {:request #{:pong}}])}
                   :check-livelock? true})]
      (is (some? result)
          "Should detect a livelock")
      (when result
        (is (= :livelock (:type result))
            "Violation type should be :livelock")
        (is (vector? (:cycle result))
            "Should include the cycle")
        (is (seq (:cycle result))
            "Cycle should not be empty")))))

(deftest livelock-after-events
  (testing "Livelock after executing some events should report both path and cycle"
    ;; Use b/step to create a proper state machine that:
    ;; 1. Waits for :setup
    ;; 2. Then loops forever with :ping/:pong
    (let [wait-then-loop
          (b/step
           (fn [state event]
             (case (or state :waiting)
               :waiting (if (= :setup event)
                          [:ping {:request #{:ping}}]
                          [:waiting {:wait-on #{:setup}}])
               :ping [:pong {:request #{:pong}}]
               :pong [:ping {:request #{:ping}}])))

          result (check/check
                  {:bthreads {:setup-once
                              ;; Requests setup, then terminates
                              (b/bids [{:request #{:setup}}])
                              :wait-then-loop
                              wait-then-loop}
                   :check-livelock? true})]
      (is (some? result)
          "Should detect a livelock")
      (when result
        (is (= :livelock (:type result))
            "Violation type should be :livelock")
        (is (vector? (:path result))
            "Should include the path")
        (is (seq (:path result))
            "Path should not be empty - should contain :setup")
        (is (= [:setup] (:path result))
            "Path should contain the :setup event before the cycle")
        (is (vector? (:cycle result))
            "Should include the cycle")
        (is (seq (:cycle result))
            "Cycle should not be empty")
        (is (= #{:ping :pong} (set (:cycle result)))
            "Cycle should contain the ping-pong events")))))

(deftest livelock-check-disabled
  (testing "When :check-livelock? is false, livelock should not be reported"
    (let [result (check/check
                  {:bthreads {:ping-pong
                              (b/round-robin [{:request #{:ping}}
                                              {:request #{:pong}}])}
                   :check-livelock? false})]
      (is (nil? result)
          "Should not detect livelock when :check-livelock? is false"))))

(deftest livelock-with-terminating-branch
  (testing "Livelock detected even when one branch terminates"
    ;; Create a scenario where:
    ;; - Bthread 1 requests :terminate (terminal event)
    ;; - Bthread 2 requests :loop-start, which leads to infinite ping-pong
    ;; At the first sync point, model checker explores both branches.
    ;; The :terminate branch leads to termination (OK).
    ;; The :loop-start branch leads to livelock (VIOLATION).
    (let [terminating-branch
          (b/bids [{:request #{{:type :terminate :terminal true}}}])

          looping-branch
          (b/step
           (fn [state event]
             (case (or state :waiting)
               :waiting (if (= :loop-start event)
                          [:ping {:request #{:ping}}]
                          [:waiting {:request #{:loop-start}}])
               :ping [:pong {:request #{:pong}}]
               :pong [:ping {:request #{:ping}}])))

          result (check/check
                  {:bthreads {:terminating terminating-branch
                              :looping looping-branch}
                   :check-livelock? true})]
      (is (some? result)
          "Should detect a livelock even though one branch terminates")
      (when result
        (is (= :livelock (:type result))
            "Violation type should be :livelock")
        (is (vector? (:path result))
            "Should include the path")
        ;; The path shows the events to reach the cycle entry.
        ;; It includes :loop-start which enters the looping branch.
        (is (some #(= :loop-start %) (:path result))
            "Path should contain :loop-start event that enters the looping branch")
        (is (vector? (:cycle result))
            "Should include the cycle")
        (is (seq (:cycle result))
            "Cycle should not be empty")
        (is (= #{:ping :pong} (set (:cycle result)))
            "Cycle should contain the ping-pong events")))))

(deftest truncation-warning-when-max-nodes-exceeded
  (testing "Should warn when state space exploration is truncated due to max-nodes limit"
    (let [;; Create a bthread that generates many unique states
          ;; Each state increments a counter and requests a unique event
          counter-bthread (b/step
                           (fn [state _event]
                             (let [n (or state 0)]
                               [(inc n) {:request #{(keyword (str "event-" n))}}])))

          result (check/check
                  {:bthreads {:counter counter-bthread}
                   :max-nodes 5})] ;; Very small limit to force truncation

      (is (some? result)
          "Should return a result when truncated")

      (when result
        (is (= :truncated (:type result))
            "Violation type should be :truncated")
        (is (= 5 (:max-nodes result))
            "Should report the max-nodes limit")
        (is (string? (:message result))
            "Should include a message about truncation")))))
