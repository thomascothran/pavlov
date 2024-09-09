(ns tech.thomascothran.pavlov.bprogram-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread.proto :as bthread]
            [tech.thomascothran.pavlov.bthread.defaults]
            [tech.thomascothran.pavlov.bprogram.defaults :as bp-defaults]
            [tech.thomascothran.pavlov.bid.defaults]
            [tech.thomascothran.pavlov.bprogram.proto :as bprogram]))

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

;; This knows too much about the internals ...
(deftest init-next-state
  (testing "Given we initialize with two bthreads
    When we get the next state
    Then it emits an init-event as the event
    And it has mapped all the bthreads to the init event"
    (let [program (bp-defaults/make-program [x-request y-request])
          !a (atom [])
          _ (bprogram/attach-handlers!
             program
             (reify bprogram/Handler
               (bprogram/id [_] 1)
               (bprogram/handle [_ event]
                 (swap! !a conj event))))
          _ (bprogram/start! program)

          result (bp-defaults/next-state [x-request y-request])]
      (is (= {:type ::bp-defaults/init-event}
             (get result ::bp-defaults/event)))
      (is (= {::bp-defaults/init-event #{x-request y-request}}
             (get result ::bp-defaults/event->handlers))))))

(keys @(:!state p))

(deftest test-next-event
  (testing "Given one bthread requesting a single event
      When we decide the next event
      That event is selected"
    (is (= :x
           (-> {:some-event #{x-request}}
               (bp-defaults/next-state {:type :some-event})
               (get ::bp-defaults/event)))))

  (testing "Given two bthreads each requesting a single event
      When we decide the next event
      The higher priority bthread wins"
    (is (= :y
           (-> {:some-event [y-request x-request]}
               (bp-defaults/next-state {:type :some-event})
               (get ::bp-defaults/event))))

    (is (= :y
           (-> {:some-event [nil-request y-request x-request]}
               (bp-defaults/next-state {:type :some-event})
               (get ::bp-defaults/event)))))

  (testing "Given one bthread requests :y
    And the other bthread blocks :y
    Then no request occurs"
    (is (nil? (-> {:some-event [x-request block-x]}
                  (bp-defaults/next-state {:type :some-event})
                  (get ::bp-defaults/event))))))

(deftest test-next-event-waiting
  (testing "Given that a bthread is not waiting on an event
    When the next state is requested
    Then it is not triggered"
    (is (-> {:some-event [x-waiting-on-y]}
            (bp-defaults/next-state {:type :another-event})
            (get ::bp-defaults/event)
            nil?)))
  (testing "Given that a bthread returns a wait
    When the next state is calculated
    Then it is added to the registry"
    (is (= {:y #{x-waiting-on-y}}
           (-> {:some-event [x-waiting-on-y]}
               (bp-defaults/next-state {:type :another-event})
               (get ::bp-defaults/registry))))))


