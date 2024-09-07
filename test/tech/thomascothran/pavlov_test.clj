(ns tech.thomascothran.pavlov-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.proto :as proto]
            [tech.thomascothran.pavlov :as b]))

(def x-request
  (with-meta {}
    {`proto/bid (constantly [#{:x}])}))

(def block-x
  (with-meta {}
    {`proto/bid (constantly [#{} #{} #{:x}])}))

(def y-request
  (with-meta {}
    {`proto/bid (constantly [#{:y}])}))

(def nil-request
  (with-meta {}
    {`proto/bid (constantly [])}))

(def x-waiting-on-y
  (with-meta {}
    {`proto/bid (constantly [#{:x} #{:y}])}))

(deftest test-next-event
  (testing "Given one bthread requesting a single event
      When we decide the next event
      That event is selected"
    (is (= :x (b/next-state [x-request] nil))))

  (testing "Given two bthreads each requesting a single event
      When we decide the next event
      The higher priority bthread wins"
    (is (= :y (b/next-state [y-request x-request] nil)))
    (is (= :y (b/next-state [nil-request y-request x-request] nil))))

  (testing "Given one bthread requests :y
    And the other bthread blocks :y
    Then no request occurs"
    (is (nil? (b/next-state [x-request block-x] nil)))))

(deftest test-next-event-waiting
  (testing "Given that an event is waiting on y to request x
    When y is not the most recent event
    Then x is not requested"
    (is (nil? (b/next-state [x-waiting-on-y] nil)))))


