(ns tech.thomascothran.pavlov.bprogram-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread :as bthread]
            [tech.thomascothran.pavlov.bthread.defaults]
            [tech.thomascothran.pavlov.bprogram.defaults :as bp-defaults]
            [tech.thomascothran.pavlov.bid.defaults]
            [tech.thomascothran.pavlov.bprogram.proto :as bprogram]))

(def x-request
  (bthread/seq [{:request #{:x}}]))

(deftest init-next-state
  (testing "Given we initialize with a bthread
    When we get the next state
    Then it emits an init-event as the event
    And it runs the next event up"
    (let [program (bp-defaults/make-program [x-request])
          _ (bprogram/start! program)
          out-queue (bprogram/out-queue program)
          _ (bprogram/stop! program)]

      (is (= [{:type :pavlov/init-event}
              {:type :x}
              {:type :pavlov/terminate}]
             (seq out-queue)))))

  (testing "Given we initialize with no bthreads
    Then it emits an init-event as the event "
    (let [program (bp-defaults/make-program [])
          _ (bprogram/start! program)
          out-queue (bprogram/out-queue program)
          _ (bprogram/stop! program)]

      (is (= [{:type :pavlov/init-event}
              {:type :pavlov/terminate}]
             (seq out-queue)))))

  (testing "Given we initialize with a bthreads that should wait
    Then it emits an init-event as the event
    And it does not emit the waiting request"
    (let [program (bp-defaults/make-program
                   [(bthread/seq [{:wait-on #{:x}
                                   :request #{:y}}])])
          _ (bprogram/start! program)
          out-queue (bprogram/out-queue program)
          _ (bprogram/stop! program)]

      (is (= [{:type :pavlov/init-event}
              {:type :y}
              {:type :pavlov/terminate}]
             (seq out-queue)))))

  (testing "Given we have several bthreads in a row
    When we run the program 
    The events should be run in sequence"
    (let [bthreads [(bthread/seq [{:request #{:x}}

                                  {:request #{:y}}
                                  {:request #{:z}}])]
          program (bp-defaults/make-program bthreads)

          _ (bprogram/start! program)
          out-queue (bprogram/out-queue program)
          _ (bprogram/stop! program)]

      (is (= [{:type :pavlov/init-event}
              {:type :x}
              {:type :y}
              {:type :z}
              {:type :pavlov/terminate}]
             (seq out-queue))))))

(deftest test-simple-wait
  (testing "Given that a bthread is waiting on one event and requesting another
    When the event it is waiting on occurs
    The request should be cancelled "
    (let [bthreads [(bthread/seq [{:request #{:x}}] {:priority 1})
                    (bthread/seq [{:request #{:z} :wait-on #{:x}}])]

          program (bp-defaults/make-program bthreads)

          _         (bprogram/start! program)
          out-queue (bprogram/out-queue program)
          _         (bprogram/stop! program)]

      (is (= [{:type :pavlov/init-event}
              {:type :x}
              {:type :pavlov/terminate}]
             (seq out-queue)))))

  (testing "Multiple waits in a row"
    (let [bthreads [(bthread/seq [{:wait-on #{:x}}
                                  {:request #{:y}}])
                    (bthread/seq [{:request #{:x}}])]

          program (bp-defaults/make-program bthreads)

          _         (bprogram/start! program)
          out-queue (bprogram/out-queue program)
          _         (bprogram/stop! program)]
      (is (= [{:type :pavlov/init-event}
              {:type :x}
              {:type :y}
              {:type :pavlov/terminate}]
             (seq out-queue))))))

(deftest good-morning-and-evening
  (let [bthreads
        [(bthread/seq (repeat 3 {:request #{:good-morning}}))
         (bthread/seq (repeat 3 {:request #{:good-evening}}))
         (bthread/seq (interleave
                       (repeat 10 {:wait-on #{:good-morning}
                                   :block #{:good-evening}})
                       (repeat 10 {:wait-on #{:good-evening}
                                   :block #{:good-morning}})))]
        program (bp-defaults/make-program bthreads)
        _         (bprogram/start! program)
        out-queue (bprogram/out-queue program)
        _         (Thread/sleep 1000)
        _         (bprogram/stop! program)]
    (def out-queue out-queue)
    (def program program)
    (is (= "?"
           (seq out-queue)))))


