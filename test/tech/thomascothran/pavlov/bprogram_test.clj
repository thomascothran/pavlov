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

(deftest init-next-state
  (testing "Given we initialize with two bthreads
    When we get the next state
    Then it emits an init-event as the event
    And it runs the next event up"
    (let [program (bp-defaults/make-program [x-request y-request])
          !produced-events (atom [])
          _ (bprogram/attach-handlers!
             program
             (reify bprogram/Handler
               (bprogram/id [_] 1)
               (bprogram/handle [_ event]
                 (swap! !produced-events conj event))))
          _ (bprogram/start! program)]

      (is (= [{:type :pavlov/init-event}
              {:type :x}]
             @!produced-events))))

  (testing "Given we initialize with no bthreads
    Then it emits an init-event as the event "
    (let [program (bp-defaults/make-program [])
          !produced-events (atom [])
          _ (bprogram/attach-handlers!
             program
             (reify bprogram/Handler
               (bprogram/id [_] 1)
               (bprogram/handle [_ event]
                 (swap! !produced-events conj event))))
          _ (bprogram/start! program)]

      (is (= [{:type :pavlov/init-event}]
             @!produced-events))))

  (testing "Given we initialize with a bthreads that should wait
    Then it emits an init-event as the event
    And it does not emit the waiting request"
    (let [program (bp-defaults/make-program [{:wait-on #{:x}
                                              :request #{:y}}])
          !produced-events (atom [])
          _ (bprogram/attach-handlers!
             program
             (reify bprogram/Handler
               (bprogram/id [_] 1)
               (bprogram/handle [_ event]
                 (swap! !produced-events conj event))))
          _ (bprogram/start! program)]

      (is (= [{:type :pavlov/init-event}
              {:type :y}]
             @!produced-events)))))

(deftest test-simple-wait
  (testing "Given that a bthread is waiting on one event and requesting another
    When the event it is waiting on occurs
    The request should be cancelled "
    (let [bthreads [{:request #{:x}}
                    {:request #{:z} :wait-on #{:x}}]
          program (bp-defaults/make-program bthreads)

          !a (atom [])

          _ (bprogram/attach-handlers!
             program
             (reify bprogram/Handler
               (bprogram/id [_] 1)
               (bprogram/handle [_ event]
                 (swap! !a conj event))))
          _ (bprogram/start! program)]

      (is (= @!a [{:type :pavlov/init-event}
                  {:type :x}])))))

#_(deftest test-next-event
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

#_(deftest test-next-event-waiting
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


