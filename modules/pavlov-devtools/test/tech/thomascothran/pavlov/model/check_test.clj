(ns tech.thomascothran.pavlov.model.check-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.model.check :as check]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]))

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
        ;; New format: check for :deadlocks vector
        (is (seq (:deadlocks result))
            "Should have deadlocks in new format")

        (when (seq (:deadlocks result))
          (is (map? (first (:deadlocks result)))
              "First deadlock should be a map"))))))

(deftest spawn-only-bthread-checked-by-model-checker
  (testing "Model checker should handle spawn-only bthreads"
    (let [spawned-event {:type :spawned :terminal true}
          spawned-bthreads {:spawned (b/bids [{:wait-on #{:start}}
                                              {:request #{spawned-event}}])}
          parent (b/bids [{:bthreads spawned-bthreads}])
          starter (b/bids [{:request #{:start}}])
          result (check/check {:bthreads [[:parent parent]
                                          [:starter starter]]
                               :check-deadlock? true})]
      (is (nil? result)
          "Should terminate without deadlock"))))

(deftest simple-deadlock
  (let [result (check/check
                {:bthreads
                 [[:test (b/bids [{:request #{{:type :a}}}
                                  {:request #{{:type :b}}}])]]
                 :check-deadlock? true})]
    ;; New format: check for :deadlocks vector
    (is (seq (:deadlocks result))
        "Should detect deadlock in new format")))

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
        ;; New format: check for :deadlocks vector
        (is (seq (:deadlocks result))
            "Should have deadlocks in new format")

        (when-let [deadlock (first (:deadlocks result))]
          (is (= [:first-event] (:path deadlock))
              "Path should show :first-event happened before deadlock"))))))

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
    ;; New format: check for :deadlocks vector
    (is (seq (:deadlocks result))
        "Should detect deadlock in new format")))

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
    ;; New format: check for violations (could be safety or deadlock)
    (is (or (seq (:safety-violations result))
            (seq (:deadlocks result)))
        "Should detect some violation in new format")))

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

(defn edge-types
  [edges]
  (mapv (comp e/type :event) edges))

(defn make-hot-terminal-bthreads
  []
  {:finisher (b/bids [{:request #{{:type :done
                                   :terminal true}}}])
   :watcher (b/bids [{:wait-on #{:done}}
                     {:hot true}])})

(defn make-hot-deadlock-bthreads
  []
  {:starter (b/bids [{:request #{:setup}}])
   :obligation (b/bids [{:wait-on #{:setup}}
                        {:hot true}])})

(defn make-hot-cycle-bthreads
  []
  (let [looper (b/step
                (fn [state event]
                  (case (or state :waiting)
                    :waiting (if (= :setup event)
                               [:ping {:request #{:ping}
                                       :hot true}]
                               [:waiting {:wait-on #{:setup}}])
                    :ping [:pong {:request #{:pong}
                                  :hot true}]
                    :pong [:ping {:request #{:ping}
                                  :hot true}])))]
    {:starter (b/bids [{:request #{:setup}}])
     :looper looper}))

(deftest test-complex-identifier-bug
  ;; Catches another case where the state of a bthread does not change
  ;; and the bids are the same. In order to identify a unique bprogram
  ;; you *also* need the event. Otherwise different bprograms in the
  ;; same state can be identified as dupes
  (let [violation (check/check {:bthreads (make-update-sync-bthreads)
                                :check-deadlock? true})]
    ;; New format: check for :deadlocks vector
    (is (seq (:deadlocks violation))
        "Should detect deadlock in new format")))

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
        ;; New format: check for :safety-violations vector
        (is (seq (:safety-violations result))
            "Should have safety violations in new format")

        (when-let [violation (first (:safety-violations result))]
          (is (= :violation (:type (:event violation)))
              "The violating event should have type :violation"))))))

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
        ;; New format: check for :safety-violations vector
        (is (seq (:safety-violations result))
            "Should have safety violations in new format")

        (when-let [violation (first (:safety-violations result))]
          (is (= [:event-a] (:path violation))
              "Path should show only :event-a was explored"))))))

(deftest directly-check-branching-on-top-level-bid
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

(deftest directly-test-branching-on-equal-priority-branches
  (testing "Model checker should follow all branches when they branch on bthreads with equal priority"
    (let [bthreads {:request-a
                    (b/bids [{:request #{{:type :a}}}])

                    :request-b
                    (b/bids [{:request #{:b}}])

                    :wait-on-a
                    (b/bids [{:wait-on #{:a}}
                             {:request #{{:type :a-confirmed
                                          :terminal true}}}])
                    :wait-on-b
                    (b/bids [{:wait-on #{:b}}
                             {:request #{{:type :b-confirmed
                                          :terminal true}}}])}
          result (check/check {:bthreads bthreads
                               :possible #{:a-confirmed
                                           :b-confirmed}})]
      (is (nil? result)))))

(deftest map-bthreads-preserve-equal-priority-branching
  (testing "Map bthreads should preserve equal-priority branching through check/check"
    (letfn [(make-map-bthreads []
              {:request-a
               (b/bids [{:request #{:a}}])

               :request-b
               (b/bids [{:request #{:b}}])

               :finish-a
               (b/bids [{:wait-on #{:a}}
                        {:request #{{:type :a-confirmed :terminal true}}
                         :block #{:b}}
                        {:block #{:b}}])

               :finish-b
               (b/bids [{:wait-on #{:b}}
                        {:request #{{:type :b-confirmed :terminal true}}
                         :block #{:a}}
                        {:block #{:a}}])})
            (make-ordered-bthreads []
              [[:request-a
                (b/bids [{:request #{:a}}])]

               [:request-b
                (b/bids [{:request #{:b}}])]

               [:finish-a
                (b/bids [{:wait-on #{:a}}
                         {:request #{{:type :a-confirmed :terminal true}}
                          :block #{:b}}
                         {:block #{:b}}])]

               [:finish-b
                (b/bids [{:wait-on #{:b}}
                         {:request #{{:type :b-confirmed :terminal true}}
                          :block #{:a}}
                         {:block #{:a}}])]])]
      (let [map-result (check/check {:bthreads (make-map-bthreads)
                                     :possible #{:a-confirmed
                                                 :b-confirmed}
                                     :check-deadlock? false})
            ordered-result (check/check {:bthreads (make-ordered-bthreads)
                                         :possible #{:a-confirmed
                                                     :b-confirmed}
                                         :check-deadlock? false})]
        (is (nil? map-result)
            "Map input should keep :request-a and :request-b at equal priority, so both confirmations are reachable")
        (is ordered-result)
        (is (= #{:b-confirmed}
               (get ordered-result :impossible)))))))

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
        ;; New format: check for :livelocks vector
        (is (seq (:livelocks result))
            "Should have livelocks in new format")

        (when-let [livelock (first (:livelocks result))]
          (is (vector? (:cycle livelock))
              "Should include the cycle")
          (is (seq (:cycle livelock))
              "Cycle should not be empty"))))))

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
        ;; New format: check for :livelocks vector
        (is (seq (:livelocks result))
            "Should have livelocks in new format")

        (when-let [livelock (first (:livelocks result))]
          (is (vector? (:path livelock))
              "Should include the path")
          (is (seq (:path livelock))
              "Path should not be empty - should contain :setup")
          (is (= [:setup] (:path livelock))
              "Path should contain the :setup event before the cycle")
          (is (vector? (:cycle livelock))
              "Should include the cycle")
          (is (seq (:cycle livelock))
              "Cycle should not be empty")
          (is (= #{:ping :pong} (set (:cycle livelock)))
              "Cycle should contain the ping-pong events"))))))

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
    ;; - One branch takes a terminal :terminate event
    ;; - Another branch takes :loop-start, which leads to infinite ping-pong
    ;; At the first sync point, model checker explores both branches.
    ;; The :terminate branch leads to termination (OK).
    ;; The :loop-start branch leads to livelock (VIOLATION).
    (let [chooser
          (b/bids [{:request #{:loop-start
                               {:type :terminate :terminal true}}}])

          looping-branch
          (b/step
           (fn [state event]
             (case (or state :waiting)
               :waiting (if (= :loop-start event)
                          [:ping {:request #{:ping}}]
                          [:waiting {:wait-on #{:loop-start}}])
               :ping [:pong {:request #{:pong}}]
               :pong [:ping {:request #{:ping}}])))

          result (check/check
                  {:bthreads {:chooser chooser
                              :looping looping-branch}
                   :check-livelock? true})]
      (is (some? result)
          "Should detect a livelock even though one branch terminates")
      (when result
        ;; New format: check for :livelocks vector
        (is (seq (:livelocks result))
            "Should have livelocks in new format")

        (when-let [livelock (first (:livelocks result))]
          (is (vector? (:path livelock))
              "Should include the path")
          ;; The path shows the events to reach the cycle entry.
          ;; It includes :loop-start which enters the looping branch.
          (is (some #(= :loop-start %) (:path livelock))
              "Path should contain :loop-start event that enters the looping branch")
          (is (vector? (:cycle livelock))
              "Should include the cycle")
          (is (seq (:cycle livelock))
              "Cycle should not be empty")
          (is (= #{:ping :pong} (set (:cycle livelock)))
              "Cycle should contain the ping-pong events"))))))

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
        ;; New format: :truncated is a boolean flag
        (is (true? (:truncated result))
            "Should have :truncated true when max-nodes exceeded")))))

(deftest truncation-suppresses-hot-liveness-violations
  (testing "Should not report hot-state liveness violations from a truncated graph"
    (let [result (check/check
                  {:bthreads
                   {:worker (b/bids [{:request #{:event-0}}
                                     {:request #{:event-1}}
                                     {:request #{:event-2}}
                                     {:request #{:event-3}}
                                     {:request #{:event-4}}
                                     {:hot true}])}
                   :max-nodes 2})]
      (is (some? result)
          "Should still return a truncation result")
      (when result
        (is (true? (:truncated result))
            "Should mark the result as truncated")
        (is (nil? (:liveness-violations result))
            "Should suppress liveness violations when exploration is truncated")
        (is (nil? (:deadlocks result))
            "Should suppress deadlocks when exploration is truncated")))))

(deftest truncation-suppresses-impossible-events
  (testing "Should not report impossible events from a truncated graph"
    (let [result (check/check
                  {:bthreads {:worker (b/bids [{:request #{:a}}
                                               {:request #{:b}}
                                               {:request #{:payment}}])}
                   :possible #{:payment}
                   :max-nodes 2})]
      (is (some? result)
          "Should still return a truncation result")
      (when result
        (is (true? (:truncated result))
            "Should mark the result as truncated")
        (is (nil? (:impossible result))
            "Should suppress impossible events when exploration is truncated")))))

(deftest hot-deadlock-reports-liveness-and-deadlock
  (testing "Hot deadlocks appear under :liveness-violations and :deadlocks"
    (let [result (check/check {:bthreads (make-hot-deadlock-bthreads)})]
      (is (some? result)
          "Should detect the hot deadlock")
      (when result
        (is (seq (:liveness-violations result))
            "Should report the hot deadlock as a liveness violation")
        (is (seq (:deadlocks result))
            "Should still report the structural deadlock")
        (when-let [violation (first (:liveness-violations result))]
          (is (some? (:node-id violation))
              "Should report the violating node")
          (is (= [:setup] (edge-types (:path-edges violation)))
              "Path witness should include the edge into the hot deadlock")
          (is (not (contains? violation :cycle-edges))
              "Deadlock witness should not include a cycle")
          (is (:hot (:state violation))
              "Violation should report the hot node state")
          (is (not (contains? violation :property)))
          (is (not (contains? violation :quantifier)))
          (is (not (contains? violation :trace))))))))

(deftest hot-cycle-liveness-violation-reported-without-livelock-check
  (testing "Hot cycles still report :liveness-violations when structural livelock checking is disabled"
    (let [result (check/check {:bthreads (make-hot-cycle-bthreads)
                               :check-livelock? false})]
      (is (some? result)
          "Should detect the hot cycle")
      (when result
        (is (seq (:liveness-violations result))
            "Should report the hot cycle as a liveness violation")
        (is (nil? (:livelocks result))
            "Should not report structural livelocks when disabled")
        (when-let [violation (first (:liveness-violations result))]
          (is (= [:setup] (edge-types (:path-edges violation)))
              "Path witness should stop at the hot cycle entry")
          (is (= [:ping :pong] (edge-types (:cycle-edges violation)))
              "Cycle witness should contain the hot cycle edges")
          (is (:hot (:state violation))
              "Violation should report the hot entry state"))))))

(deftest hot-cycle-still-reports-livelock
  (testing "Hot cycles overlap with structural :livelocks"
    (let [result (check/check {:bthreads (make-hot-cycle-bthreads)})]
      (is (some? result)
          "Should detect the hot cycle")
      (when result
        (is (seq (:liveness-violations result))
            "Should report the hot cycle as a liveness violation")
        (is (seq (:livelocks result))
            "Should still report the structural livelock")))))

(deftest possible-check-satisfied-one-path
  (testing "Possible check is satisfied when ANY path contains the event"
    ;; This is Cycle 2.6: possibility check with one satisfying path
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
                   :possible #{:payment}})]

      ;; Should return nil - no violation because payment-branch satisfies the property
      (is (nil? result)
          "Should return nil when possible check is satisfied on at least one path"))))

(deftest possible-check-violation-no-path-satisfies
  (testing "Possible check violation violation when NO path satisfies the predicate"
    (let [failure-path-a
          (b/bids [{:request #{:failure-a}}
                   {:request #{{:type :done-a :terminal true}}}])

          failure-path-b
          (b/bids [{:request #{:failure-b}}
                   {:request #{{:type :done-b :terminal true}}}])

          result (check/check
                  {:bthreads {:failure-branch-a failure-path-a
                              :failure-branch-b failure-path-b}
                   :possible #{:payment}})]

      (is (= #{:payment} (:impossible result))
          "Should detect impossible event when no path contains the requested event"))))

(deftest top-level-liveness-config-is-rejected
  (testing "Top-level :liveness config is rejected in favor of :hot and :possible"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":liveness"
         (check/check {:bthreads (make-hot-terminal-bthreads)
                       :liveness {:payment-required
                                  {:quantifier :universal
                                   :eventually #{:payment}}}})))))

(deftest possible-check-satisfied-in-cycle
  (testing "Possible checks should still succeed when the event appears in a reachable cycle"
    (let [result (check/check
                  {:bthreads {:payment-loop
                              (b/round-robin [{:request #{:payment}}
                                              {:request #{:ack}}])}
                   :check-livelock? false ;; Disable structural livelock to isolate liveness
                   :possible #{:payment}})]

      (is (nil? result)
          "Should return nil when possible check is satisfied in cycle"))))

(deftest possible-satisfied-via-spawned-environment-bthread
  (testing "Possible event check should see terminal paths reached through spawned environment bthreads"
    ;; Regression: the state-space search currently misses the :c -> ::done path when
    ;; an environment bthread spawns the bthread that requests :c.
    (let [control-result
          (check/check
           {:bthreads {::scenario
                       (b/bids [{:wait-on #{:c}}
                                {:request #{{:type ::done
                                             :terminal true}}}])}
            :environment-bthreads {::init
                                   (b/bids [{:request #{:a}}
                                            {:request #{:b}}
                                            {:request #{:c}}])}
            :possible #{::done}
            :check-deadlock? false})

          bug-result
          (check/check
           {:bthreads {::scenario
                       (b/bids [{:wait-on #{:c}}
                                {:request #{{:type ::done
                                             :terminal true}}}])}
            :environment-bthreads {::init
                                   (b/bids [{:request #{:a}}
                                            {:request #{:b}}
                                            {:bthreads
                                             {::create
                                              (b/bids [{:request #{:c}}])}}])}
            :possible #{::done}
            :check-deadlock? false})]

      (is (nil? control-result)
          "Sanity check: direct environment path to ::done should satisfy the possibility check")
      (is (nil? bug-result)
          "Spawned environment path to ::done should also satisfy the possibility check"))))

(deftest possibility-check-preserves-satisfying-branch-after-convergence
  (testing "Possibility check should succeed when one branch satisfies the property before paths converge"
    (let [result
          (check/check
           {:bthreads {::brancher (b/bids [{:request #{:a :b}}])
                       ::finisher (b/bids [{:wait-on #{:a :b}}
                                           {:request #{{:type ::done
                                                        :terminal true}}}])}
            :possible #{:a}
            :check-deadlock? false
            :check-livelock? false})]

      (is (nil? result)
          "A trace :a -> ::done exists, so possibility check for :a should pass"))))

(deftest empty-bthreads-returns-nil
  (testing "Empty bthreads returns deadlock (empty program has no events and cannot terminate)"
    ;; What happens when we call check with no bthreads?
    ;; Actual behavior: Returns {:type :deadlock ...}
    ;; Rationale: An empty program has no events to execute and no way to reach
    ;; a terminal state, so it's technically deadlocked at the initial state.
    (let [result-map (check/check {:bthreads {}})]
      ;; New format: check for :deadlocks vector
      (is (seq (:deadlocks result-map))
          "Empty bthreads map should return deadlock in new format")

      (when-let [deadlock (first (:deadlocks result-map))]
        (is (= [] (:path deadlock))
            "Path should be empty (deadlocked at initial state)")
        (is (= {} (get-in deadlock [:state :bthread->bid]))
            "State should have no bthreads")))

    (let [result-vec (check/check {:bthreads []})]
      ;; New format: check for :deadlocks vector
      (is (seq (:deadlocks result-vec))
          "Empty bthreads vector should also return deadlock in new format")

      (when-let [deadlock (first (:deadlocks result-vec))]
        (is (= [] (:path deadlock))
            "Path should be empty (deadlocked at initial state)")))))

;; =============================================================================
;; Phase 4: New return format tests
;; =============================================================================

(deftest new-format-single-deadlock
  (testing "Single deadlock returns in new format {:deadlocks [...]}"
    (let [result (check/check
                  {:bthreads {:simple
                              (b/bids [{:request #{{:type :a}}}
                                       {:request #{{:type :b}}}])}
                   :check-deadlock? true})]

      (is (some? result)
          "Should detect deadlock")

      (when result
        ;; New format: no top-level :type key
        (is (nil? (:type result))
            "Result should NOT have top-level :type key in new format")

        ;; New format: :deadlocks is a vector
        (is (vector? (:deadlocks result))
            "Result should have :deadlocks as a vector")

        (is (= 1 (count (:deadlocks result)))
            "Should have exactly one deadlock")

        (let [deadlock (first (:deadlocks result))]
          ;; Individual violation does NOT have :type key
          (is (nil? (:type deadlock))
              "Individual deadlock should NOT have :type key")

          (is (vector? (:path deadlock))
              "Deadlock should have :path")

          (is (map? (:state deadlock))
              "Deadlock should have :state"))))))

(deftest new-format-single-livelock
  (testing "Single livelock returns in new format {:livelocks [...]}"
    (let [result (check/check
                  {:bthreads {:ping-pong
                              (b/round-robin [{:request #{:ping}}
                                              {:request #{:pong}}])}
                   :check-livelock? true})]

      (is (some? result)
          "Should detect livelock")

      (when result
        ;; New format: no top-level :type key
        (is (nil? (:type result))
            "Result should NOT have top-level :type key in new format")

        ;; New format: :livelocks is a vector
        (is (vector? (:livelocks result))
            "Result should have :livelocks as a vector")

        (is (= 1 (count (:livelocks result)))
            "Should have exactly one livelock")

        (let [livelock (first (:livelocks result))]
          ;; Individual violation does NOT have :type key
          (is (nil? (:type livelock))
              "Individual livelock should NOT have :type key")

          (is (vector? (:path livelock))
              "Livelock should have :path")

          (is (vector? (:cycle livelock))
              "Livelock should have :cycle")

          (is (= #{:ping :pong} (set (:cycle livelock)))
              "Cycle should contain ping-pong events"))))))

(deftest new-format-single-safety-violation
  (testing "Single safety violation returns in new format {:safety-violations [...]}"
    (let [result (check/check
                  {:bthreads {:violator
                              (b/bids [{:request #{{:type :violation
                                                    :invariant-violated true}}}])}})]

      (is (some? result)
          "Should detect safety violation")

      (when result
        ;; New format: no top-level :type key
        (is (nil? (:type result))
            "Result should NOT have top-level :type key in new format")

        ;; New format: :safety-violations is a vector
        (is (vector? (:safety-violations result))
            "Result should have :safety-violations as a vector")

        (is (= 1 (count (:safety-violations result)))
            "Should have exactly one safety violation")

        (let [violation (first (:safety-violations result))]
          ;; Individual violation does NOT have :type key
          (is (nil? (:type violation))
              "Individual safety violation should NOT have :type key")

          (is (map? (:event violation))
              "Safety violation should have :event")

          (is (= :violation (:type (:event violation)))
              "Event should have type :violation")

          (is (vector? (:path violation))
              "Safety violation should have :path")

          (is (map? (:state violation))
              "Safety violation should have :state"))))))

(deftest new-format-single-liveness-violation
  (testing "Single hot-state liveness violation returns the direct witness shape"
    (let [result (check/check {:bthreads (make-hot-terminal-bthreads)})]

      (is (some? result)
          "Should detect liveness violation")

      (when result
        ;; New format: no top-level :type key
        (is (nil? (:type result))
            "Result should NOT have top-level :type key in new format")

        ;; New format: :liveness-violations is a vector
        (is (vector? (:liveness-violations result))
            "Result should have :liveness-violations as a vector")

        (is (= 1 (count (:liveness-violations result)))
            "Should have exactly one liveness violation")

        (let [violation (first (:liveness-violations result))]
          ;; Individual violation does NOT have :type key
          (is (nil? (:type violation))
              "Individual liveness violation should NOT have :type key")

          (is (some? (:node-id violation))
              "Liveness violation should have :node-id")

          (is (= [:done] (edge-types (:path-edges violation)))
              "Path edges should witness the hot terminal event")

          (is (not (contains? violation :cycle-edges))
              "Hot terminal violation should not include a cycle witness")

          (is (map? (:state violation))
              "Liveness violation should have :state")

          (is (:hot (:state violation))
              "Violating state should be hot")

          (is (not (contains? violation :property)))
          (is (not (contains? violation :quantifier)))
          (is (not (contains? violation :trace))))))))

(deftest new-format-no-violations
  (testing "No violations returns nil"
    (let [result (check/check
                  {:bthreads {:terminate-successfully
                              (b/bids [{:request #{{:type :done :terminal true}}}])}})]

      (is (nil? result)
          "Should return nil when no violations exist"))))

(deftest new-format-truncated
  (testing "Truncation adds :truncated true to result map"
    (let [;; Create a bthread that generates many unique states
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
        ;; New format: no top-level :type key
        (is (nil? (:type result))
            "Result should NOT have top-level :type key in new format")

        ;; New format: :truncated is a boolean flag
        (is (true? (:truncated result))
            "Result should have :truncated true when max-nodes exceeded")

        ;; Old format had :max-nodes and :message - new format just has :truncated flag
        (is (nil? (:max-nodes result))
            "New format should NOT have :max-nodes key")

        (is (nil? (:message result))
            "New format should NOT have :message key")))))

(deftest new-format-multiple-violation-types
  (testing "Multiple violation types (livelock AND safety) all returned in categorized map"
    ;; This test creates a scenario where both a livelock AND a safety violation exist
    ;; We do this by having a nondeterministic choice at the first sync point:
    ;; - If :start-loop is chosen, we enter an infinite ping-pong loop (livelock)
    ;; - If :start-violation is chosen, we trigger a safety violation
    ;; The model checker explores BOTH branches and should find BOTH violations
    (let [result (check/check
                  {:bthreads
                   {:choice
                    ;; At the first sync point, we can either start the loop OR trigger violation
                    (b/bids [{:request #{:start-loop :start-violation}}])

                    :looper
                    ;; If :start-loop is chosen, loop forever on ping-pong
                    (b/step
                     (fn [state event]
                       (case (or state :waiting)
                         :waiting (if (= :start-loop event)
                                    [:ping {:request #{:ping}}]
                                    [:waiting {:wait-on #{:start-loop}}])
                         :ping [:pong {:request #{:pong}}]
                         :pong [:ping {:request #{:ping}}])))

                    :violator
                    ;; If :start-violation is chosen, trigger a safety violation
                    (b/on :start-violation
                          (constantly {:request #{{:type :bad :invariant-violated true}}}))}})]

      (is (some? result)
          "Should detect violations")

      (when result
        ;; New format: no top-level :type key
        (is (nil? (:type result))
            "Result should NOT have top-level :type key in new format")

        ;; Should have BOTH categories
        (is (vector? (:livelocks result))
            "Result should have :livelocks vector")

        (is (vector? (:safety-violations result))
            "Result should have :safety-violations vector")

        (is (= 1 (count (:livelocks result)))
            "Should have one livelock")

        (is (= 1 (count (:safety-violations result)))
            "Should have one safety violation")

        ;; Verify livelock
        (let [livelock (first (:livelocks result))]
          (is (vector? (:cycle livelock))
              "Livelock should have :cycle")
          (is (= #{:ping :pong} (set (:cycle livelock)))
              "Cycle should contain ping-pong events"))

        ;; Verify safety violation
        (let [violation (first (:safety-violations result))]
          (is (= :bad (:type (:event violation)))
              "Safety violation event should have type :bad"))))))
