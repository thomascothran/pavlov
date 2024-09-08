(ns tech.thomascothran.pavlov-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread.proto :as bthread]
            [tech.thomascothran.pavlov.bthread.defaults]
            [tech.thomascothran.pavlov.bid.defaults]
            [tech.thomascothran.pavlov :as b]))

(def x-request
  {:request #{:x}})

(def block-x
  {:block #{:x}})

(def y-request
  (with-meta {:name :y-request}
    {`bthread/bid (constantly {:request #{:y}})}))

(def nil-request
  {})

(def x-waiting-on-y
  {:wait-on #{:y}
   :request #{:x}})

(deftest init-next-state
  (testing "Given we initialize with two bthreads
    When we get the next state
    Then it emits an init-event as the event
    And it has mapped all the bthreads to the init event"
    (let [result (b/next-state [x-request y-request])]
      (is (= {:type ::b/init-event} (get result ::b/event)))
      (is (= {::b/init-event #{x-request y-request}}
             (get result ::b/registry))))))

(deftest test-next-event
  (testing "Given one bthread requesting a single event
      When we decide the next event
      That event is selected"
    (is (= :x
           (-> {:some-event #{x-request}}
               (b/next-state {:type :some-event})
               (get ::b/event)))))

  (testing "Given two bthreads each requesting a single event
      When we decide the next event
      The higher priority bthread wins"
    (is (= :y
           (-> {:some-event [y-request x-request]}
               (b/next-state {:type :some-event})
               (get ::b/event))))

    (is (= :y
           (-> {:some-event [nil-request y-request x-request]}
               (b/next-state {:type :some-event})
               (get ::b/event)))))

  (testing "Given one bthread requests :y
    And the other bthread blocks :y
    Then no request occurs"
    (is (nil? (-> {:some-event [x-request block-x]}
                  (b/next-state {:type :some-event})
                  (get ::b/event))))))

(deftest test-next-event-waiting
  (testing "Given that a bthread is not waiting on an event
    When the next state is requested
    Then it is not triggered"
    (is (-> {:some-event [x-waiting-on-y]}
            (b/next-state {:type :another-event})
            (get ::b/event)
            nil?)))
  (testing "Given that a bthread returns a wait
    When the next state is calculated
    Then it is added to the registry"
    (is (= {:y #{x-waiting-on-y}}
           (-> {:some-event [x-waiting-on-y]}
               (b/next-state {:type :another-event})
               (get ::b/registry))))))


