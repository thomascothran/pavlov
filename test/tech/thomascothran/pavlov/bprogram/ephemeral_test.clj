(ns tech.thomascothran.pavlov.bprogram.ephemeral-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.defaults]
            [tech.thomascothran.pavlov.bprogram.ephemeral-test.bthreads :as tb]
            [tech.thomascothran.pavlov.bprogram.proto :as bp]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
            [tech.thomascothran.pavlov.event :as event]))

(deftest subscriber-should-receive-event-after-bthread-executes
  (let [!stack  (atom [])
        bthread (b/step
                 ::test-subscriber-call-order
                 (fn [_ event]
                   (swap! !stack conj [:bthread event])
                   [nil {:wait-on #{:test-event}}]))
        subscriber (fn [x _] (swap! !stack conj [:subscriber x]))
        program   (bpe/make-program! [bthread]
                                     {:subscribers {:test subscriber}})
        _ (bp/submit-event! program :test-event)
        _ @(bp/stop! program)]
    (is (= [[:bthread nil]
            [:bthread :test-event]
            [:subscriber :test-event]]
           (butlast @!stack)))))

(deftest good-morning-and-evening
  (let [bthreads
        [(b/reprise 4
                    {:request #{:good-morning}})

         (b/reprise 4 {:request #{:good-evening}})
         (b/interlace
          [{:wait-on #{:good-morning}
            :block #{:good-evening}}
           {:wait-on #{:good-evening}
            :block #{:good-morning}}])]

        !a        (atom [])
        subscriber  (fn [x _] (swap! !a conj x))
        program   (bpe/make-program! bthreads
                                     {:subscribers {:test subscriber}})
        return      @(bp/stop! program)]

    (is (= (interleave (repeat 4 :good-morning)
                       (repeat 4 :good-evening))
           (butlast @!a)))
    (is (= {:type :pavlov/terminate
            :terminal true}
           return))))

(deftest add-subscriber
  (let [bthreads [(b/bids [{:wait-on #{:go}}
                           {:request #{:some-event}}])]

        !a         (atom [])
        subscriber (fn [x _] (swap! !a conj x))
        program    (bpe/make-program! bthreads)
        _          (bp/subscribe! program :test subscriber)
        _          (bp/submit-event! program :go)
        _          @(bp/stop! program)]
    (is (= [:go :some-event]
           (butlast  @!a)))))

;; Note that we test our behavioral threads in isolation
;; from the bprogram.
(deftest test-winning-bthreads
  (testing "Given a bthread that watches a crossing win pattern for player x
    When that crossing pattern is filled in by player x
    Then the bthread requests a win event"
    (let [bthread (tb/make-winning-bthreads
                   #{[0 0 :x] [2 2 :x] [1 1 :x]})
          bid1 (b/bid bthread nil) ;; initialization
          bid2 (b/bid bthread {:type [1 1 :x]})
          bid3 (b/bid bthread {:type [2 2 :x]})
          bid4 (b/bid bthread {:type [0 0 :x]})]

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
  (let [bthreads (mapv tb/make-winning-bthreads tb/winning-event-set)
        events   [{:type [0 0 :o]}
                  {:type [1 1 :o]}
                  {:type [2 2 :o]}]
        !a        (atom [])
        subscriber  (fn [x _] (swap! !a conj x))
        program  (bpe/make-program! bthreads
                                    {:subscribers {:test subscriber}})
        _        (doseq [event events]
                   (bp/submit-event! program event))
        _        @(bp/stop! program)

        expected (conj events {:terminal true, :type [:o :wins]})
        actual   (take 5 @!a)]
    (is (= expected actual))))

(deftest test-simple-computer-picks
  (let [bthreads
        (reduce into []
                [(mapv tb/make-winning-bthreads tb/winning-event-set)
                 (tb/make-no-double-placement-bthreads)
                 [(tb/make-computer-picks-bthreads :o)
                  (b/bids [{:type [0 0 :o]}])]])

        !a        (atom [])
        subscriber  (fn [x _] (swap! !a conj x))
        program (bpe/make-program! bthreads
                                   {:subscribers {:test subscriber}})

        _        @(bp/stop! program)
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
  ;; Problem is that blocked events
  ;; are being represented both as a map with a :type key
  ;; and as the type itself. Can we support both?
  (let [bthreads
        (reduce into [(tb/make-computer-picks-bthreads :o)
                      (tb/make-enforce-turn-bthreads)]
                [(mapv tb/make-winning-bthreads tb/winning-event-set)
                 (tb/make-no-double-placement-bthreads)])

        !a        (atom [])
        subscriber (fn [x _] (swap! !a conj x))
        program (bpe/make-program! bthreads
                                   {:subscribers {:test subscriber}})
        _        (bp/submit-event! program {:type [1 1 :x]})
        _        @(bp/stop! program)]

    (is (= [{:type [1 1 :x]} {:type [0 0 :o]}]
           (butlast @!a)))))

(deftest test-sync-call
  (let [bthreads
        [(b/bids [{:request #{:a}}])
         (b/bids [{:wait-on #{:a}}
                  {:request #{:b}}])
         (b/bids [{:wait-on #{:b}}
                  {:request #{{:type :c
                               :terminal true}}}])]

        return-value @(bpe/execute! bthreads)]
    (is (= {:type :c
            :terminal true}
           return-value))))
