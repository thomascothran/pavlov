(ns tech.thomascothran.pavlov-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.proto :as proto]
            [tech.thomascothran.pavlov :as b]))

(def x-request
  (with-meta {:name :x-request}
    {`proto/bid (constantly [#{:x}])}))

(def block-x
  (with-meta {:name :block-x}
    {`proto/bid (constantly [#{} #{} #{:x}])}))

(def y-request
  (with-meta {:name :y-request}
    {`proto/bid (constantly [#{:y}])}))

(def nil-request
  (with-meta {}
    {`proto/bid (constantly [])}))

(def x-waiting-on-y
  (with-meta {}
    {`proto/bid (constantly [#{:x} #{:y}])}))

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
  (testing "Given that an event is not waiting on an event
    When the next state is requested
    Then it is not triggered"
    (is (-> {:some-event [x-waiting-on-y]}
            (b/next-state {:type :another-event})
            (get ::b/event)
            nil?))))


