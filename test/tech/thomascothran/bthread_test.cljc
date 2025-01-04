(ns tech.thomascothran.bthread-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [tech.thomascothran.pavlov.bthread :as bthread]
            [tech.thomascothran.pavlov.bthread.defaults]))

(deftest test-serde-on-maps
  (let [bthread {:name :test-bthread
                 :request #{:test-event}}]
    (is (= bthread
           (->> bthread
                bthread/serialize
                (bthread/deserialize bthread))))))

(deftest test-serde-on-nil
  (is (= nil
         (->> nil
              bthread/serialize
              (bthread/deserialize nil)))))

(deftest test-bid-sequence
  (let [abc [{:name `request-a
              :request #{:a}}
             {:name `request-b
              :request #{:b}}
             {:name `request-c
              :request #{:c}}]
        bthread (bthread/seq abc)]
    (is (= (first abc)
           (bthread/bid bthread {:type :test})))
    (is (= (second abc)
           (bthread/bid bthread {:type :test})))
    (is (= (-> (nth abc 2) bthread/name)
           (-> bthread
               (bthread/bid {:type :test})
               (bthread/name))))
    (is (nil? (bthread/bid bthread {:type :test})))))

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
      (is (= 3 (bthread/serialize bthread))
          "Should initialize state correctly")
      (is (= {:wait-on #{:test}}
             (bthread/bid bthread {:type :test}))
          "Should return the correct bid after initialization")
      (is (= 2 (bthread/serialize bthread))
          "Should decrement state")))
  (testing "should handle round trip serialization"
    (let [bthread (bthread/step count-down-step-fn)
          _       (bthread/bid bthread nil)
          _       (bthread/bid bthread {:type :test})
          ser     (bthread/serialize bthread)
          de      (bthread/deserialize bthread ser)]
      (is (= 2 ser de))))
  (testing "should work with anonymous functions"
    (let [bthread (bthread/step #(apply count-down-step-fn %&))]
      (is (= {:wait-on #{:test}}
             (bthread/bid bthread nil))
          "Should return the correct bid")
      (is (= 3 (bthread/serialize bthread))
          "Should initialize state correctly")
      (is (= {:wait-on #{:test}}
             (bthread/bid bthread {:type :test}))
          "Should return the correct bid after initialization")
      (is (= 2 (bthread/serialize bthread))
          "Should decrement state"))))

