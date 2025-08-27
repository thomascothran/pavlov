(ns tech.thomascothran.pavlov.bthread-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [tech.thomascothran.pavlov.bthread :as bthread]
            [tech.thomascothran.pavlov.bthread.defaults]))

(deftest test-serde-on-maps
  (let [bthread {:name :test-bthread
                 :request #{:test-event}}]
    (is (= bthread
           (->> bthread
                bthread/state
                (bthread/set-state bthread))))))

(deftest test-serde-on-nil
  (is (= nil
         (->> nil
              bthread/state
              (bthread/set-state nil)))))

(deftest test-bid-sequence
  (let [abc [{:name `request-a
              :request #{:a}}
             {:name `request-b
              :request #{:b}}
             {:name `request-c
              :request #{:c}}]
        bthread (bthread/bids abc)]
    (is (= (first abc)
           (bthread/bid bthread {:type :test})))
    (is (= (second abc)
           (bthread/bid bthread {:type :test})))
    (bthread/bid bthread {:type :test})
    (is (nil? (bthread/bid bthread {:type :test})))))

(deftest test-reprise
  (let [bthread (bthread/reprise :forever {:request #{:test}})
        _ (doseq [_ (range 3)]
            (bthread/bid bthread {:type :test}))]
    (is (= {:request #{:test}}
           (bthread/bid bthread {:type :test}))))

  (let [bthread (bthread/reprise 3 {:request #{:test}})
        _ (doseq [_ (range 3)]
            (bthread/bid bthread {:type :test}))]
    (is (= nil (bthread/bid bthread {:type :test})))))

(deftest test-fuse
  (let [bid-a {:request #{:test-a
                          :wait-on #{:trigger}}}
        bid-b {:request #{:test-b
                          :wait-on #{:trigger}}}
        bthread (bthread/interlace
                 [bid-a
                  (bthread/bids [bid-b bid-b])])
        bid1 (bthread/bid bthread :trigger)
        bid2 (bthread/bid bthread :trigger)
        bid3 (bthread/bid bthread :trigger)
        bid4 (bthread/bid bthread :trigger)
        bid5 (bthread/bid bthread :trigger)
        bid6 (bthread/bid bthread :trigger)]
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
    (let [bthread (bthread/step count-down-step-fn)]
      (is (= {:wait-on #{:test}}
             (bthread/bid bthread nil))
          "Should return the correct bid")
      (is (= 3 (bthread/state bthread))
          "Should initialize state correctly")
      (is (= {:wait-on #{:test}}
             (bthread/bid bthread {:type :test}))
          "Should return the correct bid after initialization")
      (is (= 2 (bthread/state bthread))
          "Should decrement state")))
  (testing "should handle round trip serialization"
    (let [bthread (bthread/step count-down-step-fn)
          _ (bthread/bid bthread nil)
          _ (bthread/bid bthread {:type :test})
          ser (bthread/state bthread)
          de (bthread/set-state bthread ser)]
      (is (= 2 ser de))))
  (testing "should work with anonymous functions"
    (let [bthread (bthread/step #(apply count-down-step-fn %&))]
      (is (= {:wait-on #{:test}}
             (bthread/bid bthread nil))
          "Should return the correct bid")
      (is (= 3 (bthread/state bthread))
          "Should initialize state correctly")
      (is (= {:wait-on #{:test}}
             (bthread/bid bthread {:type :test}))
          "Should return the correct bid after initialization")
      (is (= 2 (bthread/state bthread))
          "Should decrement state"))))

(deftest test-step-function-error
  (testing "When a bthread step function throws an error
    Should emit a terminal event with an error
    And that event should be terminal"
    (let [divide-by-0-step-fn (fn [& _] (/ 1 0))

          event {:type :some-event}

          bid (bthread/bid (bthread/step divide-by-0-step-fn)
                           event)

          requests (get bid :request)
          error-event (first requests)]
      (is (= 1 (count requests)))
      (is (get error-event :terminal))
      (is (get error-event :error)))))
