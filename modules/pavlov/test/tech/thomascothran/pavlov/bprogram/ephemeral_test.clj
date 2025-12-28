(ns tech.thomascothran.pavlov.bprogram.ephemeral-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.defaults]
            [tech.thomascothran.pavlov.bprogram.ephemeral-test.bthreads :as tb]
            [tech.thomascothran.pavlov.bprogram.proto :as bp]
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
