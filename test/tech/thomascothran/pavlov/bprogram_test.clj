(ns tech.thomascothran.pavlov.bprogram-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread.proto :as bthread.proto]
            [tech.thomascothran.pavlov.bthread :as bthread]
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
    {`bthread.proto/bid (constantly {:request #{:y}})}))

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
             @!produced-events))))
  (testing "Given we have several bthreads in a row
    When we run the program 
    The events should be run in sequence"
    (let [bthreads [(bthread/seq [{:request #{:x}}
                                  {:request #{:y}}
                                  {:request #{:x}}])]
          program (bp-defaults/make-program bthreads)

          !produced-events (atom [])

          _ (bprogram/attach-handlers!
             program
             (reify bprogram/Handler
               (bprogram/id [_] 1)
               (bprogram/handle [_ event]
                 (swap! !produced-events conj event))))
          _ (bprogram/start! program)]

      (is (= [{:type :pavlov/init-event}
              {:type :x}
              {:type :y}
              {:type :z}]
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
                  {:type :x}]))))
  (testing "Multiple waits in a row"
    (let [bthreads [(bthread/seq [{:wait-on #{:x}}
                                  {:request #{:y}}])
                    (bthread/seq [{:request #{:x}}])]

          program (bp-defaults/make-program bthreads)

          !a (atom [])

          _ (bprogram/attach-handlers!
             program
             (reify bprogram/Handler
               (bprogram/id [_] 1)
               (bprogram/handle [_ event]
                 (swap! !a conj event))))
          _ (bprogram/start! program)]
      (def a @!a)
      (is (= @!a [{:type :pavlov/init-event}
                  {:type :x}
                  {:type :y}])))))

