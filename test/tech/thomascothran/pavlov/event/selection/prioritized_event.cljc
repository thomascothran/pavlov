(ns tech.thomascothran.pavlov.event.selection.prioritized-event
  (:require #?(:clj [clojure.test :refer [deftest is run-tests]]
               :cljs [cljs.test :refer [deftest is run-tests]])
            [tech.thomascothran.pavlov.event.defaults]
            [tech.thomascothran.pavlov.bid.defaults]
            [tech.thomascothran.pavlov.bthread.defaults]
            [tech.thomascothran.pavlov.event.selection :as selection]))

(deftest test-single-winning-bid
  (let [bthread-a {:request #{{:type :a}}}
        bthread->bid {bthread-a bthread-a}
        bthreads-by-priority [bthread-a]
        result
        (selection/prioritized-event bthreads-by-priority
                                     bthread->bid)]
    (is (= {:type :a} result))))

(comment
  (test-single-winning-bid))

(deftest test-multiple-bids-one-winner
  (let [bthread-a {:request #{{:type :a}}}
        bthread-b {:request #{{:type :b}}}
        bthread-c {:request #{{:type :c}}}
        bthreads-by-priority [bthread-b bthread-c bthread-a]
        bthread->bid (into {}
                           (map (fn [bthread] [bthread bthread]))
                           bthreads-by-priority)]

    (is (= {:type :b}
           (selection/prioritized-event bthreads-by-priority
                                        bthread->bid)))))

(comment
  (test-multiple-bids-one-winner))

(deftest test-prioritization
  (let [bthread-a {:request #{{:type :a}}}
        bthread-b {:request #{{:type :b}}}
        bthread-c {:request #{{:type :c}}
                   :block #{:b}}
        bthreads-by-priority [bthread-c bthread-a bthread-b]
        bthread->bid (into {}
                           (map (fn [bthread] [bthread bthread]))
                           bthreads-by-priority)]
    (is (= {:type :c}
           (selection/prioritized-event bthreads-by-priority
                                        bthread->bid)))))

(comment
  (test-prioritization))

(deftest test-all-blocked
  (let [bthread-a {:request #{{:type :a}}
                   :block #{:b}}
        bthread-b {:request #{{:type :b}}
                   :block #{:a}}
        bthreads-by-priority [bthread-a bthread-b]
        bthread->bid (into {}
                           (map (fn [bthread] [bthread bthread]))
                           bthreads-by-priority)]
    (is (nil?
         (selection/prioritized-event bthreads-by-priority
                                      bthread->bid)))))
