(ns tech.thomascothran.pavlov.event.selection.prioritized-events-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [tech.thomascothran.pavlov.event.selection :as sel]))

(deftest ordered-bthreads-ordered-requests
  (let [bthreads-by-priority [:A :B]
        bid-a {:request [:d :e :f]}
        bid-b {:request [:x]}
        bthread->bid {:A bid-a
                      :B bid-b}
        events (sel/prioritized-events bthreads-by-priority
                                       bthread->bid)]
    (is (= [:d] events))))

(deftest unordered-bthreads-ordered-requests
  (let [bthreads-by-priority #{:A :B}
        bid-a {:request [:d :e :f]}
        bid-b {:request [:x]}
        bthread->bid {:A bid-a
                      :B bid-b}
        events (sel/prioritized-events bthreads-by-priority
                                       bthread->bid)]
    (is (= [:d :x] events))))

(deftest unordered-bthreads-unordered-events
  (let [bthreads-by-priority #{:A :B}
        bid-a {:request #{:d :e :f}}
        bid-b {:request [:x :y]}
        bthread->bid {:A bid-a
                      :B bid-b}
        events (sel/prioritized-events bthreads-by-priority
                                       bthread->bid)]
    (is (= #{:d :e :f :x} (into #{} events)))))

(deftest unordered-bthreads-ordered-requests-with-blocks
  (let [bthreads-by-priority #{:A :B :C}
        bid-a {:request [:d :e :f]}
        bid-b {:request [:x]}
        bid-c {:request [:g]
               :block [:x]}
        bthread->bid {:A bid-a
                      :B bid-b
                      :C bid-c}
        events (sel/prioritized-events bthreads-by-priority
                                       bthread->bid)]
    (is (= [:d :g] events))))

(deftest ordered-requests-skip-blocked-higher-priority-events
  (let [bthreads-by-priority [:requester :blocker]
        bthread->bid {:requester {:request [:blocked :allowed]}
                      :blocker {:block [:blocked]}}]
    (is (= [:allowed]
           (sel/prioritized-events bthreads-by-priority bthread->bid)))
    (is (= :allowed
           (sel/prioritized-event bthreads-by-priority bthread->bid)))
    (is (= :allowed
           (sel/prioritized-event bthreads-by-priority
                                  bthread->bid
                                  #{:blocked}))
        "The three-argument API reuses a blocked set computed by its caller.")))
