(ns tech.thomascothran.pavlov.bthread-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bthread.defaults]))

(deftest test-serde-on-maps
  (let [bthread {:name :test-bthread
                 :request #{:test-event}}]
    (is (= bthread
           (->> bthread
                b/state
                (b/set-state bthread))))))

(deftest test-serde-on-nil
  (is (= nil
         (->> nil
              b/state
              (b/set-state nil)))))

(deftest test-bid-sequence
  (let [abc [{:name `request-a
              :request #{:a}}
             {:name `request-b
              :request #{:b}}
             {:name `request-c
              :request #{:c}}]
        bthread (b/bids abc)]
    (is (= (first abc)
           (b/notify! bthread {:type :test})))
    (is (= (second abc)
           (b/notify! bthread {:type :test})))
    (b/notify! bthread {:type :test})
    (is (nil? (b/notify! bthread {:type :test})))))

(deftest test-repeat
  (let [bthread (b/repeat {:request #{:test}})
        _ (doseq [_ (range 3)]
            (b/notify! bthread {:type :test}))]
    (is (= {:request #{:test}}
           (b/notify! bthread {:type :test}))))

  (let [bthread (b/repeat 3 {:request #{:test}})
        _ (doseq [_ (range 3)]
            (b/notify! bthread {:type :test}))]
    (is (= nil (b/notify! bthread {:type :test})))))

(deftest test-fuse
  (let [bid-a {:request #{:test-a
                          :wait-on #{:trigger}}}
        bid-b {:request #{:test-b
                          :wait-on #{:trigger}}}
        bthread (b/round-robin
                 [bid-a
                  (b/bids [bid-b bid-b])])
        bid1 (b/notify! bthread :trigger)
        bid2 (b/notify! bthread :trigger)
        bid3 (b/notify! bthread :trigger)
        bid4 (b/notify! bthread :trigger)
        bid5 (b/notify! bthread :trigger)
        bid6 (b/notify! bthread :trigger)]
    (is (= bid-a bid1 bid3 bid5))
    (is (= bid-b bid2 bid4))
    (is (nil? bid6))))

(defn count-down-step-fn
  [prev-state _event]
  (if prev-state
    [(dec prev-state) {:wait-on #{:test}}]
    [3 {:wait-on #{:test}}]))

(deftest test-step-function
  (testing "Should retain state"
    (let [bthread (b/step count-down-step-fn)]
      (is (= {:wait-on #{:test}}
             (b/notify! bthread nil))
          "Should return the correct bid")
      (is (= 3 (b/state bthread))
          "Should initialize state correctly")
      (is (= {:wait-on #{:test}}
             (b/notify! bthread {:type :test}))
          "Should return the correct bid after initialization")
      (is (= 2 (b/state bthread))
          "Should decrement state")))
  (testing "should handle round trip serialization"
    (let [bthread (b/step count-down-step-fn)
          _ (b/notify! bthread nil)
          _ (b/notify! bthread {:type :test})
          ser (b/state bthread)
          de (b/set-state bthread ser)]
      (is (= 2 ser de))))
  (testing "should work with anonymous functions"
    (let [bthread (b/step #(apply count-down-step-fn %&))]
      (is (= {:wait-on #{:test}}
             (b/notify! bthread nil))
          "Should return the correct bid")
      (is (= 3 (b/state bthread))
          "Should initialize state correctly")
      (is (= {:wait-on #{:test}}
             (b/notify! bthread {:type :test}))
          "Should return the correct bid after initialization")
      (is (= 2 (b/state bthread))
          "Should decrement state"))))

(deftest test-step-function-error
  (testing "When a bthread step function throws an error
    Should emit a terminal event with an error
    And that event should be terminal"
    (let [divide-by-0-step-fn (fn [& _] (/ 1 0))

          event {:type :some-event}

          bid (b/notify! (b/step divide-by-0-step-fn)
                         event)

          requests (get bid :request)
          error-event (first requests)]
      (is (= 1 (count requests)))
      (is (get error-event :terminal))
      (is (get error-event :error)))))

(deftest test-on-every
  (let [!events (atom [])
        bthread
        (b/on-every #{:test-event}
                    (fn [event]
                      (swap! !events conj event)
                      {:request #{:test-event-received}}))
        init-bid (b/notify! bthread nil) ;; initialize
        bid (b/notify! bthread {:type :test-event})
        ;; because :test-event-received was requested, the
        ;; bthread will be notified. However, `f` should not
        ;; be invoked - unless you want an endless loop
        _ (b/notify! bthread {:type :test-event-received})]

    (is (= {:wait-on #{:test-event}} init-bid))
    (is (= [{:type :test-event}] @!events))

    (is (= {:wait-on #{:test-event}
            :request #{:test-event-received}}
           bid))))

(deftest simple-thread-test
  (let [bthread
        (b/thread [prev-state event]
                  :pavlov/init
                  [prev-state {:wait-on #{:event-a}}]

                  :event-a
                  [prev-state {:request #{{:type  :event-b}}}])
        bid1 (b/notify! bthread nil)
        bid2 (b/notify! bthread {:type :event-a})]
    (is (= {:wait-on #{:event-a}} bid1))
    (is (= {:request #{{:type  :event-b}}} bid2))))
