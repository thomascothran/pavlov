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

(deftest universal-liveness-violation-on-terminating-path
  (testing "Universal liveness property violated when path terminates without satisfying predicate"
    ;; This is Cycle 2.1: simplest liveness check
    ;; A bthread that terminates immediately without doing anything else
    ;; The liveness property requires :payment to occur on ALL paths
    ;; Expected: liveness violation because no :payment event occurred
    (let [result (check/check
                  {:bthreads {:terminate-immediately
                              (b/bids [{:request #{{:type :done :terminal true}}}])}
                   :liveness
                   {:payment-required
                    {:quantifier :universal
                     :predicate (fn [trace]
                                  ;; Use pavlov.event/type to handle both keywords and maps
                                  (some #(= :payment (tech.thomascothran.pavlov.event/type %)) trace))}}})]

      ;; The test SHOULD fail because :liveness is not implemented yet
      ;; Either result will be nil (no violation detected) OR
      ;; an error will be thrown about unknown option
      (is (some? result)
          "Should detect liveness violation")

      (when result
        (is (= :liveness-violation (:type result))
            "Violation type should be :liveness-violation")
        (is (= :payment-required (:property result))
            "Should identify which property was violated")
        (is (= :universal (:quantifier result))
            "Should report the quantifier used")
        (is (vector? (:trace result))
            "Should include the trace that violated the property")
        (is (= [:done] (:trace result))
            "Trace should show the path that terminated without payment")))))

(deftest universal-liveness-satisfied-on-terminating-path
  (testing "Universal liveness property satisfied when payment occurs before termination"
    ;; This is Cycle 2.2: liveness property is satisfied
    ;; A bthread that requests :payment first, then terminates
    ;; The liveness property requires :payment to occur on ALL paths
    ;; Expected: nil (no violation) because :payment occurred before termination
    (let [result (check/check
                  {:bthreads {:payment-then-terminate
                              (b/bids [{:request #{:payment}}
                                       {:request #{{:type :done :terminal true}}}])}
                   :liveness
                   {:payment-required
                    {:quantifier :universal
                     :predicate (fn [trace]
                                  ;; Use pavlov.event/type to handle both keywords and maps
                                  (some #(= :payment (tech.thomascothran.pavlov.event/type %)) trace))}}})]

      ;; Should return nil - no violation
      (is (nil? result)
          "Should return nil when liveness property is satisfied"))))

(deftest universal-liveness-eventually-shorthand
  (testing "Universal liveness using :eventually shorthand for common case"
    ;; This is Cycle 2.3: syntactic sugar for event type checking
    ;; Same as Cycle 2.1, but using :eventually #{:payment} instead of :predicate
    ;; A bthread that terminates immediately without doing anything else
    ;; The liveness property requires :payment to occur on ALL paths using the shorthand
    ;; Expected: same liveness violation as Cycle 2.1
    (let [result (check/check
                  {:bthreads {:terminate-immediately
                              (b/bids [{:request #{{:type :done :terminal true}}}])}
                   :liveness
                   {:payment-required
                    {:quantifier :universal
                     :eventually #{:payment}}}})]

      ;; The test SHOULD fail because :eventually is not implemented yet
      ;; Either result will be nil (no violation detected) OR
      ;; an error will be thrown about unknown option
      (is (some? result)
          "Should detect liveness violation")

      (when result
        (is (= :liveness-violation (:type result))
            "Violation type should be :liveness-violation")
        (is (= :payment-required (:property result))
            "Should identify which property was violated")
        (is (= :universal (:quantifier result))
            "Should report the quantifier used")
        (is (vector? (:trace result))
            "Should include the trace that violated the property")
        (is (= [:done] (:trace result))
            "Trace should show the path that terminated without payment")))))

(deftest universal-liveness-violation-in-cycle
  (testing "Universal liveness property violated when trapped in cycle without satisfying event"
    ;; This is Cycle 2.4: liveness violation in a cycle
    ;; A bthread that loops forever on :ping/:pong
    ;; The liveness property requires :payment to occur on ALL paths
    ;; Expected: liveness violation because the cycle never contains :payment
    ;; Note: We disable structural livelock checking to isolate liveness behavior
    (let [result (check/check
                  {:bthreads {:ping-pong
                              (b/round-robin [{:request #{:ping}}
                                              {:request #{:pong}}])}
                   :check-livelock? false ;; Disable structural livelock to isolate liveness
                   :liveness
                   {:payment-required
                    {:quantifier :universal
                     :eventually #{:payment}}}})]

      ;; The test SHOULD fail because liveness checking in cycles is not implemented yet
      (is (some? result)
          "Should detect liveness violation in cycle")

      (when result
        (is (= :liveness-violation (:type result))
            "Violation type should be :liveness-violation (not :livelock)")
        (is (= :payment-required (:property result))
            "Should identify which property was violated")
        (is (= :universal (:quantifier result))
            "Should report the quantifier used")
        (is (vector? (:cycle result))
            "Should include the cycle that violated the property")
        (is (= #{:ping :pong} (set (:cycle result)))
            "Cycle should show the ping-pong events that loop forever without payment")))))

(deftest universal-liveness-satisfied-in-cycle-still-reports-livelock
  (testing "Universal liveness satisfied in cycle, but structural livelock still reported"
    ;; This is Cycle 2.5: liveness property IS satisfied within the cycle
    ;; A bthread that loops forever on :payment/:ack/:payment...
    ;; The liveness property requires :payment to occur on ALL paths
    ;; Expected: structural livelock is reported because the program loops forever
    ;;           BUT NOT a liveness violation because :payment does occur in the cycle
    ;; Design decision: structural livelocks are always reported regardless of liveness properties
    (let [result (check/check
                  {:bthreads {:payment-loop
                              (b/round-robin [{:request #{:payment}}
                                              {:request #{:ack}}])}
                   :check-livelock? true ;; Enable structural livelock checking (default)
                   :liveness
                   {:payment-required
                    {:quantifier :universal
                     :eventually #{:payment}}}})]

      ;; Should detect structural livelock
      (is (some? result)
          "Should detect a structural livelock even though liveness is satisfied")

      (when result
        (is (= :livelock (:type result))
            "Violation type should be :livelock (NOT :liveness-violation)")
        (is (vector? (:cycle result))
            "Should include the cycle")
        ;; Verify that the cycle contains :payment, which satisfies the liveness property
        (is (some #(= :payment %) (:cycle result))
            "Cycle should contain :payment event, satisfying the liveness property")
        ;; Verify the cycle is what we expect
        (is (= #{:payment :ack} (set (:cycle result)))
            "Cycle should contain :payment and :ack events")))))

(deftest existential-liveness-satisfied-one-path
  (testing "Existential liveness property satisfied when ANY path satisfies it"
    ;; This is Cycle 2.6: existential quantifier with one satisfying path
    ;; Two branches:
    ;; - One branch requests :failure then terminates (no :payment)
    ;; - Other branch requests :payment then terminates
    ;; The liveness property requires :payment to occur on AT LEAST ONE path
    ;; Expected: nil (no violation) because the :payment path satisfies the property

    ;; Strategy: Two bthreads that both offer events at the first sync point
    ;; This creates branching that the model checker will explore
    (let [failure-path
          (b/bids [{:request #{:failure}}
                   {:request #{{:type :done-a :terminal true}}}])

          payment-path
          (b/bids [{:request #{:payment}}
                   {:request #{{:type :done-b :terminal true}}}])

          result (check/check
                  {:bthreads {:failure-branch failure-path
                              :payment-branch payment-path}
                   :liveness
                   {:payment-possible
                    {:quantifier :existential
                     :eventually #{:payment}}}})]

      ;; Should return nil - no violation because payment-branch satisfies the property
      (is (nil? result)
          "Should return nil when existential liveness property is satisfied on at least one path"))))

(deftest existential-liveness-violation-no-path-satisfies
  (testing "Existential liveness violation when NO path satisfies the predicate"
    ;; This is Cycle 2.7: existential quantifier with NO satisfying paths
    ;; Two branches:
    ;; - One branch requests :failure-a then terminates (no :payment)
    ;; - Other branch requests :failure-b then terminates (no :payment)
    ;; The liveness property requires :payment to occur on AT LEAST ONE path
    ;; Expected: liveness-violation because neither path has :payment

    ;; Strategy: Two bthreads that both offer events at the first sync point
    ;; This creates branching that the model checker will explore
    (let [failure-path-a
          (b/bids [{:request #{:failure-a}}
                   {:request #{{:type :done-a :terminal true}}}])

          failure-path-b
          (b/bids [{:request #{:failure-b}}
                   {:request #{{:type :done-b :terminal true}}}])

          result (check/check
                  {:bthreads {:failure-branch-a failure-path-a
                              :failure-branch-b failure-path-b}
                   :liveness
                   {:payment-possible
                    {:quantifier :existential
                     :eventually #{:payment}}}})]

      ;; Should return liveness-violation because NO path satisfies the property
      (is (= :liveness-violation (:type result))
          "Should detect liveness violation when no path satisfies existential property")
      (is (= :payment-possible (:property result))
          "Should identify which property was violated")
      (is (= :existential (:quantifier result))
          "Should report the existential quantifier"))))

(deftest multiple-liveness-properties-reports-first-violation
  (testing "Multiple liveness properties checked, reporting first violation found"
    ;; This is Cycle 2.8: multiple liveness properties
    ;; A bthread that requests :payment then terminates (no :shipment)
    ;; Two liveness properties:
    ;; - :payment-ok requires :payment on ALL paths — SATISFIED
    ;; - :shipment-ok requires :shipment on ALL paths — VIOLATED
    ;; Expected: violation for :shipment-ok (not :payment-ok)
    (let [result (check/check
                  {:bthreads {:payment-then-terminate
                              (b/bids [{:request #{:payment}}
                                       {:request #{{:type :done :terminal true}}}])}
                   :liveness
                   {:payment-ok
                    {:quantifier :universal
                     :eventually #{:payment}}
                    :shipment-ok
                    {:quantifier :universal
                     :eventually #{:shipment}}}})]

      ;; Should detect liveness violation for :shipment-ok
      (is (some? result)
          "Should detect liveness violation")

      (when result
        (is (= :liveness-violation (:type result))
            "Violation type should be :liveness-violation")
        (is (= :shipment-ok (:property result))
            "Should identify :shipment-ok as the violated property (not :payment-ok)")
        (is (= :universal (:quantifier result))
            "Should report the quantifier used")
        (is (vector? (:trace result))
            "Should include the trace that violated the property")
        ;; The trace should show :payment then :done
        ;; :payment satisfies :payment-ok, but :shipment is never requested
        (is (= #{:payment :done} (set (:trace result)))
            "Trace should contain :payment and :done events")))))

(deftest priority-livelock-before-liveness-violation
  (testing "Structural livelock is reported before liveness violation"
    ;; This is Cycle 2.9: priority order when both violations exist
    ;; A bthread that loops forever on :ping/:pong (structural livelock)
    ;; Liveness property requires :payment to occur on ALL paths
    ;; Expected: :livelock violation (NOT :liveness-violation)
    ;; Rationale: Structural livelock is more severe (pegs CPU at 100%)
    ;;            User should know about the infinite loop first
    (let [result (check/check
                  {:bthreads {:ping-pong
                              (b/round-robin [{:request #{:ping}}
                                              {:request #{:pong}}])}
                   :check-livelock? true ;; Enable structural livelock checking (default)
                   :liveness
                   {:payment-required
                    {:quantifier :universal
                     :eventually #{:payment}}}})]

      ;; Should detect structural livelock (not liveness violation)
      (is (some? result)
          "Should detect a violation")

      (when result
        (is (= :livelock (:type result))
            "Violation type should be :livelock (NOT :liveness-violation) - structural livelock has priority")
        (is (vector? (:cycle result))
            "Should include the cycle")
        (is (= #{:ping :pong} (set (:cycle result)))
            "Cycle should contain the ping-pong events that loop forever")))))

(deftest universal-liveness-predicate-checks-cycles
  (testing "Universal liveness with PREDICATE (not :eventually) should check cycles for violations"
    ;; A bthread that loops forever on :ping/:pong (infinite cycle)
    ;; Liveness property with CUSTOM PREDICATE (not :eventually shorthand) requires :payment
    ;; Expected: :liveness-violation because cycle doesn't contain :payment
    ;; Current behavior: Returns nil (BUG — cycles not checked with :predicate)
    ;;
    ;; We disable structural livelock checking to isolate liveness behavior
    (let [result (check/check
                  {:bthreads {:ping-pong
                              (b/round-robin [{:request #{:ping}}
                                              {:request #{:pong}}])}
                   :check-livelock? false ;; Disable structural livelock to isolate liveness
                   :liveness
                   {:payment-required
                    {:quantifier :universal
                     ;; Use CUSTOM PREDICATE (not :eventually) to expose the bug
                     :predicate (fn [trace]
                                  (some #(= :payment (tech.thomascothran.pavlov.event/type %)) trace))}}})]

      ;; This test SHOULD fail with current implementation
      ;; because cycles are not checked when using :predicate
      (is (some? result)
          "Should detect liveness violation in cycle when using :predicate")

      (when result
        (is (= :liveness-violation (:type result))
            "Violation type should be :liveness-violation (not :livelock)")
        (is (= :payment-required (:property result))
            "Should identify which property was violated")
        (is (= :universal (:quantifier result))
            "Should report the quantifier used")
        (is (vector? (:cycle result))
            "Should include the cycle that violated the property")
        (is (= #{:ping :pong} (set (:cycle result)))
            "Cycle should show the ping-pong events that loop forever without payment")))))

(deftest existential-liveness-predicate-checks-cycles
  (testing "Existential liveness with PREDICATE (not :eventually) should check cycles for satisfaction"
    ;; A bthread that loops forever on :payment/:ack (infinite cycle)
    ;; Liveness property with CUSTOM PREDICATE (not :eventually shorthand) requires :payment to occur on SOME path
    ;; Expected: nil (NO violation) because the cycle DOES contain :payment, satisfying the existential property
    ;; Current buggy behavior: Returns {:type :liveness-violation ...} (BUG — cycles not checked, only terminating paths)
    ;;
    ;; We disable structural livelock checking to isolate liveness behavior
    (let [result (check/check
                  {:bthreads {:payment-loop
                              (b/round-robin [{:request #{:payment}}
                                              {:request #{:ack}}])}
                   :check-livelock? false ;; Disable structural livelock to isolate liveness
                   :liveness
                   {:payment-possible
                    {:quantifier :existential
                     ;; Use CUSTOM PREDICATE (not :eventually) to expose the bug
                     :predicate (fn [trace]
                                  (some #(= :payment (tech.thomascothran.pavlov.event/type %)) trace))}}})]

      ;; This test SHOULD fail with current implementation
      ;; because cycles are not checked when using :predicate — only terminating paths
      ;; The bug causes a false positive: it reports a violation when there shouldn't be one
      (is (nil? result)
          "Should return nil when existential liveness property is satisfied in cycle using :predicate"))))

(deftest universal-liveness-checks-deadlock-paths
  (testing "Universal liveness property should be checked on paths that end in deadlock"
    ;; This is Cycle 3.3: Liveness on Deadlock Path
    ;; A bthread that requests :process then deadlocks (no terminal event, no more bids)
    ;; Liveness property requires :payment to occur on ALL paths
    ;; Expected: :liveness-violation because the deadlock path doesn't have :payment
    ;; Current behavior: Returns {:type :deadlock ...} (liveness not checked on deadlock paths)
    ;;
    ;; Key insight: Since liveness has higher priority than deadlock in our priority order,
    ;; if we properly check liveness on deadlock paths, we should get a liveness violation,
    ;; NOT a deadlock.
    (let [result (check/check
                  {:bthreads {:process-then-deadlock
                              ;; Request :process then deadlock (no more bids, no terminal event)
                              (b/bids [{:request #{:process}}])}
                   :check-deadlock? true ;; Enable deadlock checking
                   :liveness
                   {:payment-required
                    {:quantifier :universal
                     :eventually #{:payment}}}})]

      ;; This test SHOULD fail with current implementation
      ;; Expected: {:type :liveness-violation ...}
      ;; Actual: {:type :deadlock ...}
      (is (some? result)
          "Should detect a violation (either liveness or deadlock)")

      (when result
        (is (= :liveness-violation (:type result))
            "Violation type should be :liveness-violation (NOT :deadlock) - liveness has higher priority")
        (is (= :payment-required (:property result))
            "Should identify which property was violated")
        (is (= :universal (:quantifier result))
            "Should report the quantifier used")
        (is (vector? (:trace result))
            "Should include the trace that violated the property")
        (is (= [:process] (:trace result))
            "Trace should show the :process event before deadlock, without :payment")))))

(deftest invalid-quantifier-throws-error
  (testing "Invalid quantifier (typo) throws clear error"
    ;; User provides :univrsal (missing 'e') instead of :universal
    ;; Current: Throws IllegalArgumentException: No matching clause: :univrsal
    ;; Expected: Either a clear error message OR graceful handling
    ;;
    ;; For now, we document and test the current behavior (throwing an exception)
    ;; We can improve the error message later if needed
    (is (thrown-with-msg?
         IllegalArgumentException
         #"No matching clause"
         (check/check
          {:bthreads {:simple (b/bids [{:request #{{:type :done :terminal true}}}])}
           :liveness
           {:payment-required
            {:quantifier :univrsal ;; TYPO - should be :universal
             :eventually #{:payment}}}}))
        "Should throw IllegalArgumentException for invalid quantifier")))

(deftest empty-bthreads-returns-nil
  (testing "Empty bthreads returns deadlock (empty program has no events and cannot terminate)"
    ;; What happens when we call check with no bthreads?
    ;; Actual behavior: Returns {:type :deadlock ...}
    ;; Rationale: An empty program has no events to execute and no way to reach
    ;; a terminal state, so it's technically deadlocked at the initial state.
    (let [result-map (check/check {:bthreads {}})]
      (is (= :deadlock (:type result-map))
          "Empty bthreads map should return deadlock")
      (is (= [] (:path result-map))
          "Path should be empty (deadlocked at initial state)")
      (is (= {} (get-in result-map [:state :bthread->bid]))
          "State should have no bthreads"))

    (let [result-vec (check/check {:bthreads []})]
      (is (= :deadlock (:type result-vec))
          "Empty bthreads vector should also return deadlock")
      (is (= [] (:path result-vec))
          "Path should be empty (deadlocked at initial state)"))))
