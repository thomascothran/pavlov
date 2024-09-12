(ns tech.thomascothran.bthread-test
  (:require [clojure.test :refer [deftest is]]
            [tech.thomascothran.pavlov.bthread :as bthread]))

(deftest test-bid-sequence
  (let [abc [{:request #{:a}} {:request #{:b}} {:request #{:c}}]
        bthread (bthread/seq abc)]
    (is (= {:request #{:a}} (bthread/bid bthread {:type :test})))
    (is (= {:request #{:b}} (bthread/bid bthread {:type :test})))
    (is (= {:request #{:c}} (bthread/bid bthread {:type :test})))
    (is (nil? (bthread/bid bthread {:type :test})))
    (is (= abc [{:request #{:a}} {:request #{:b}} {:request #{:c}}]))))
