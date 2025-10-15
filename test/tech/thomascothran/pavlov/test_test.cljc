(ns tech.thomascothran.pavlov.test-test
  (:require [clojure.test :refer [deftest is]]
            [tech.thomascothran.pavlov.test :as ptest]
            [tech.thomascothran.pavlov.bthread :as b]))

(deftest test-scenario-success
  (let [bthreads
        [[:event-a (b/bids [{:request #{:event-a}}])]
         [:event-b (b/bids [{:wait-on #{:event-a}}
                            {:request #{:event-b}}])]
         [:event-c (b/bids [{:wait-on #{:event-b}}
                            {:request #{:event-c}}])]]

        result (ptest/scenario bthreads [:event-a :event-b :event-c])]
    (is (get result :success))))

(deftest test-scenario-fail
  (let [bthreads
        {:event-a (b/bids [{:request #{:event-a}}])
         :event-b (b/bids [{:wait-on #{:event-a}}
                           {:request #{:event-b}}])

         :event-d (b/bids [{:wait-on #{:event-b}}
                           {:request #{:event-d}}])
         :event-e (b/bids [{:wait-on #{:event-b}}
                           {:request #{:event-e}}])}

        result (ptest/scenario bthreads [:event-a :event-b :event-c])]
    (is (= false (get result :success)))
    (is (= :event-b (get result :stuck-at)))
    (is (= #{:event-d :event-e}
           (into #{}
                 (map #(get % :pavlov/event))
                 (get result :available-branches))))))
