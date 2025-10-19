(ns tech.thomascothran.pavlov.test-test
  (:require [clojure.test :refer [deftest is]]
            [tech.thomascothran.pavlov.test :as ptest]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.bthread :as b]))

(deftest test-scenario-success
  (let [bthreads
        [[:event-a (b/bids [{:request #{:event-a}}])]
         [:event-b (b/bids [{:wait-on #{:event-a}}
                            {:request #{:event-b}}])]
         [:event-c (b/bids [{:wait-on #{:event-b}}
                            {:request #{:event-c}}])]]

        result (ptest/scenario bthreads [:event-a :event-b :event-c])]
    (is (get result :success))
    (is (= [:event-a :event-b :event-c]
           (->> (get result :execution-path)
                (take 3))))))

(deftest allow-skips
  (let [bthreads
        [[:event-a (b/bids [{:request #{:event-a}}])]
         [:event-b (b/bids [{:wait-on #{:event-a}}
                            {:request #{:event-b}}])]
         [:event-c (b/bids [{:wait-on #{:event-b}}
                            {:request #{:event-c}}])]]

        result (ptest/scenario bthreads [:event-a :event-c])]
    (is (get result :success))
    (is (= [:event-a :event-b :event-c]
           (->> (get result :execution-path)
                (take 3))))))

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
    (is (= {:request #{:event-e}}
           (get-in result [:bthread->bid :event-e]))
        "Should have bthread->bid mapping")
    (is (nil?
         (get-in result [:bthread->bid
                         :event-a])))))

(deftest test-branch-with-same-event-types
  (let [bthreads
        {:event-a (b/bids [{:request #{:event-a}}])
         :branch (b/bids
                  [{:wait-on #{:event-a}}
                   {:request #{{:type :event-b
                                :flag false}}}])
         :event-b-handler
         (b/on :event-b
               (fn [{:keys [flag]}]
                 (when flag
                   {:request #{:terminate}})))}

        event-selector
        (fn [event]
          (and (= :event-b
                  (e/type event))
               (get event :flag)))
        result (ptest/scenario bthreads [:event-a
                                         event-selector
                                         :terminate])]
    (is (not (get result :success)))
    (is (= :event-a (get result :stuck-at)))))
