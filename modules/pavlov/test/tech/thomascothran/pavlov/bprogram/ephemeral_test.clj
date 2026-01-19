(ns tech.thomascothran.pavlov.bprogram.ephemeral-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.defaults]
            [tech.thomascothran.pavlov.bprogram.ephemeral-test.bthreads :as tb]
            [tech.thomascothran.pavlov.bprogram.proto :as bp]
            [tech.thomascothran.pavlov.bprogram :as bprogram]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
            [tech.thomascothran.pavlov.event :as event]))

(deftest subscriber-should-receive-event-after-bthread-executes
  (let [!stack (atom [])
        bthread (b/step
                 (fn [_ event]
                   (swap! !stack conj [:bthread event])
                   [nil {:wait-on #{:test-event}}]))
        subscriber (fn [x _] (swap! !stack conj [:subscriber x]))
        program (bpe/make-program! {:test-bthread bthread}
                                   {:subscribers {:test subscriber}})
        _ (bp/submit-event! program :test-event)
        _ @(bp/stop! program)]
    (is (= [[:bthread nil]
            [:bthread :test-event]
            [:subscriber :test-event]]
           (butlast @!stack)))))

(deftest good-morning-and-evening
  (let [bthreads
        {:good-morning
         (b/repeat 4
                   {:request #{:good-morning}})

         :good-evening
         (b/repeat 4 {:request #{:good-evening}})

         :interlace
         (b/round-robin
          [{:wait-on #{:good-morning}
            :block #{:good-evening}}
           {:wait-on #{:good-evening}
            :block #{:good-morning}}])}

        !a (atom [])
        subscriber (fn [x _] (swap! !a conj x))
        program
        (bpe/make-program! bthreads
                           {:subscribers {:test subscriber}})
        return @(bp/stop! program)]

    (is (= (interleave (repeat 4 :good-morning)
                       (repeat 4 :good-evening))
           (butlast @!a)))
    (is (= {:type :pavlov/terminate
            :terminal true}
           return))))

(deftest add-subscriber
  (let [bthreads {:wait-on-go
                  (b/bids [{:wait-on #{:go}}
                           {:request #{:some-event}}])}

        !a (atom [])
        subscriber (fn [x _] (swap! !a conj x))
        program (bpe/make-program! bthreads)
        _ (bp/subscribe! program :test subscriber)
        _ (bp/submit-event! program :go)
        _ @(bp/stop! program)]
    (is (= [:go :some-event]
           (butlast @!a)))))

;; Note that we test our behavioral threads in isolation
;; from the bprogram.
(deftest test-winning-bthreads
  (testing "Given a bthread that watches a crossing win pattern for player x
    When that crossing pattern is filled in by player x
    Then the bthread requests a win event"
    (let [bthread (tb/make-winning-bthreads
                   #{[0 0 :x] [2 2 :x] [1 1 :x]})
          bid1 (b/notify! bthread nil) ;; initialization
          bid2 (b/notify! bthread {:type [1 1 :x]})
          bid3 (b/notify! bthread {:type [2 2 :x]})
          bid4 (b/notify! bthread {:type [0 0 :x]})]

      (is (= #{:wait-on}
             (set (keys bid1))
             (set (keys bid2))
             (set (keys bid3)))
          "The first three bids should just wait")
      (is (= #{{:type [:x :wins] :terminal true}}
             (:request bid4))
          "The last bid should request a win, because all the winning moves have been made"))))

;; Let's see if it can detect a win!
;; We'll ignore player moves for now.
(deftest tic-tac-toe-simple-win
  (let [bthreads (into []
                       (map
                        (fn [events]
                          [[:winning-bthreads events]
                           (tb/make-winning-bthreads events)]))
                       tb/winning-event-set)
        events [{:type [0 0 :o]}
                {:type [1 1 :o]}
                {:type [2 2 :o]}]
        !a (atom [])
        subscriber (fn [x _] (swap! !a conj x))
        program
        (bpe/make-program! bthreads
                           {:subscribers {:test subscriber}})

        _ (doseq [event events]
            (bp/submit-event! program event))
        _ @(bp/stopped program)

        expected (conj events {:terminal true, :type [:o :wins]})
        actual (take 5 @!a)]
    (is (= expected actual))))

(deftest test-simple-computer-picks
  ;; This needs names
  (let [winning-bthreads (for [event-set tb/winning-event-set]
                           [[:winning-bthreads event-set]
                            (tb/make-winning-bthreads event-set)])

        no-double-placement (tb/make-no-double-placement-bthreads)

        other-bthreads [[:computer-o-picks (tb/make-computer-picks-bthreads :o)]
                        [:o-top-left-corner (b/bids [{:type [0 0 :o]}])]]

        bthreads (reduce into []
                         [winning-bthreads
                          no-double-placement
                          other-bthreads])

        !a (atom [])
        subscriber (fn [x _] (swap! !a conj x))
        program
        (bpe/make-program! bthreads
                           {:subscribers {:test subscriber}})

        _ @(bp/stop! program)
        out-events @!a]
    (is (= 4 (count out-events)))
    (is (= #{:o} (->> out-events
                      (take 3)
                      (mapv (comp last event/type))
                      set))
        "The first three events should be o moves")
    (is (= [:o :wins]
           (event/type (last out-events))))))

(deftest test-taking-turns
  (let [winning-bthreads (for [event-set tb/winning-event-set]
                           [[:winning-bthreads event-set]
                            (tb/make-winning-bthreads event-set)])

        no-double-placement (tb/make-no-double-placement-bthreads)

        other-bthreads [[:computer-o-picks (tb/make-computer-picks-bthreads :o)]
                        [:enforce-turns (tb/make-enforce-turn-bthreads)]]

        bthreads (reduce into []
                         [winning-bthreads
                          no-double-placement
                          other-bthreads])

        !a (atom [])
        subscriber (fn [x _] (swap! !a conj x))
        program (bpe/make-program! bthreads
                                   {:subscribers {:test subscriber}})
        _ (bp/submit-event! program {:type [1 1 :x]})
        _ @(bp/stop! program)]

    (is (= [{:type [1 1 :x]} {:type [0 0 :o]}]
           (butlast @!a)))))

(deftest test-sync-call
  (let [bthreads
        {:request-a
         (b/bids [{:request #{:a}}])

         :request-b
         (b/bids [{:wait-on #{:a}}
                  {:request #{:b}}])
         :request-c
         (b/bids [{:wait-on #{:b}}
                  {:request #{{:type :c
                               :terminal true}}}])}

        return-value @(bpe/execute! bthreads)]
    (is (= {:type :c
            :terminal true}
           return-value))))

;; Test that a subscriber can return an `:event` which will be handled by the program
(deftest test-subscriber-returning-event
  (let [bthreads
        {:request-a
         (b/bids [{:request #{:a}}])

         :request-b
         (b/bids [{:wait-on #{:a}}
                  {:request #{:b}}])

         :block-deadlock
         {:block #{:tech.thomascothran.pavlov.bprogram.ephemeral/deadlock}}}

        !a (atom [])

        log-subscriber
        (fn [event _]
          (swap! !a conj event))

        event-subscriber
        (fn [_event _]
          {:event {:type :c
                   :terminal true}})

        opts {:subscribers {:a event-subscriber
                            :log-subscriber
                            log-subscriber}}

        return-value @(bpe/execute! bthreads opts)]
    (is (= {:type :c
            :terminal true}
           return-value))))

(deftest check-terminate-on-deadlock-works
  (is (:terminal
       @(bpe/execute! {:wait-forever (b/bids [{:wait-on #{:godot}}])}
                      {:terminate-on-deadlock true}))))

(deftest check-kill-after-works
  (is true
      @(bpe/execute! {:wait-forever (b/bids [{:wait-on #{:godot}}])}
                     {:kill-after 50})))

(deftest test-wait-and-request-same-event
  (let [bthread
        (b/bids [{:request #{:a}
                  :wait-on #{:a}}
                 {:request #{{:type :b
                              :terminal true}}}])
        result @(bpe/execute! {:test-bthread bthread})]
    (is (= {:type :b, :terminal true}
           result))))

(deftest test-adding-bthreads
  (testing "Given that a one bthread returns a request for *more* bthreads
    When the bprogram runs
    Then the new bthreads are created"
    (let [new-bthread-request {:type :new-bthread-request
                               :terminal true}
          new-bthreads {:new-bthread (b/bids [{:request #{new-bthread-request}}])}
          original-bthreads {:original-bthread (b/bids [{:request #{:a}}
                                                        {:request #{:b}
                                                         :bthreads new-bthreads}])}
          result @(bpe/execute! original-bthreads)]
      (is (= new-bthread-request
             result)))))

(deftest test-spawn-only-on-initial-bid
  (testing "Given a bthread that spawns children on init with no request, wait, or block
    Then the first event should come from existing bthreads"
    (let [spawned-event {:type :spawned :terminal true}
          spawned-bthreads {:spawned (b/bids [{:request #{spawned-event}}])}
          parent (b/bids [{:bthreads spawned-bthreads}])
          starter (b/bids [{:request #{:start}}])
          !events (atom [])
          !bthread-sets (atom [])
          subscriber (fn [event program]
                       (swap! !events conj event)
                       (when (= :start (event/type event))
                         (swap! !bthread-sets conj
                                (set (keys (bprogram/bthread->bids program))))))
          result @(bpe/execute! [[:spawner parent]
                                 [:starter starter]]
                                {:subscribers {:log subscriber}})
          first-bthreads (first @!bthread-sets)]
      (is (= spawned-event
             result))
      (is (= [:start :spawned]
             (map event/type @!events)))
      (is (not (contains? first-bthreads :spawner)))
      (is (contains? first-bthreads :spawned)))))

(deftest test-spawn-only-on-final-bid
  (testing "Given a bthread that spawns children as its last bid
    Then the spawned child should preempt lower-priority requests"
    (let [spawned-event {:type :spawned :terminal true}
          spawned-bthreads {:spawned (b/bids [{:request #{spawned-event}}])}
          parent (b/bids [{:request #{:start}}
                          {:bthreads spawned-bthreads}])
          other-terminal {:type :other :terminal true}
          other (b/bids [{:wait-on #{:start}}
                         {:request #{other-terminal}}])
          !events (atom [])
          result @(bpe/execute! [[:parent parent]
                                 [:other other]]
                                {:subscribers {:log (fn [event _]
                                                      (swap! !events conj event))}})]
      (is (= spawned-event
             result))
      (is (= [:start :spawned]
             (map event/type @!events))))))

(deftest test-spawned-bthreads-priority-splicing
  (testing "Given a parent that spawns multiple children
    Then children should run before lower-priority bthreads"
    (let [child-a (b/bids [{:request #{:child-a}}])
          child-b (b/bids [{:request #{:child-b}}])
          parent (b/bids [{:request #{:start}}
                          {:bthreads {:child-a child-a
                                      :child-b child-b}}])
          after (b/bids [{:wait-on #{:start}}
                         {:request #{{:type :after
                                     :terminal true}}}])
          !events (atom [])
          result @(bpe/execute! [[:parent parent]
                                 [:after after]]
                                {:subscribers {:log (fn [event _]
                                                      (when event
                                                        (swap! !events conj
                                                               (event/type event))))}})
          events @!events]
      (is (= {:type :after
              :terminal true}
             result))
      (is (= 4 (count events)))
      (is (= :start (first events)))
      (is (= #{:child-a :child-b}
             (set (take 2 (rest events)))))
      (is (= :after (last events))))))

(deftest test-spawned-bthread-waits-for-event
  (testing "Given a spawned bthread that waits on an event
    Then it should only run after the event occurs again"
    (let [spawned-event {:type :spawned :terminal true}
          spawned-bthreads {:spawned (b/bids [{:wait-on #{:go}}
                                              {:request #{spawned-event}}])}
          parent (b/bids [{:bthreads spawned-bthreads}])
          trigger (b/bids [{:request #{:go}}
                           {:request #{:go}}])
          !events (atom [])
          result @(bpe/execute! [[:spawner parent]
                                 [:trigger trigger]]
                                {:subscribers {:log (fn [event _]
                                                      (swap! !events conj event))}})]
      (is (= spawned-event
             result))
      (is (= [:go :go :spawned]
             (map event/type @!events))))))
