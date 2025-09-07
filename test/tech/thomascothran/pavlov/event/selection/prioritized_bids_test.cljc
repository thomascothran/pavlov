(ns tech.thomascothran.pavlov.event.selection.prioritized-bids-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [tech.thomascothran.pavlov.event.selection :as sel]))

(deftest ordered-bthreads-ordered-requests
  (let [bthreads-by-priority [:A :B]
        bid-a {:request [:d :e :f]}
        bid-b {:request [:x]}
        bthread->bid {:A bid-a
                      :B bid-b}
        bids (sel/prioritized-bids bthreads-by-priority
                                   bthread->bid)]
    (is (= [bid-a] bids)
        "With ordered bthreads and ordered requests, take the first unblocked")))

(deftest unordered-bthreads-ordered-requests
  (let [bthreads-by-priority #{:A :B}
        bid-a {:request [:d :e :f]}
        bid-b {:request [:x]}
        bthread->bid {:A bid-a
                      :B bid-b}
        bids (sel/prioritized-bids bthreads-by-priority
                                   bthread->bid)]
    (is (= [bid-a bid-b] bids)
        "With ordered bthreads and ordered requests, take the first unblocked")))

(deftest unordered-bthreads-ordered-requests-with-blocks
  (let [bthreads-by-priority #{:A :B :C}
        bid-a {:request [:d :e :f]}
        bid-b {:request [:x]}
        bid-c {:request [:g]
               :block [:x]}
        bthread->bid {:A bid-a
                      :B bid-b
                      :C bid-c}
        bids (sel/prioritized-bids bthreads-by-priority
                                   bthread->bid)]
    (is (= [bid-a bid-c] bids)
        "With ordered bthreads and ordered requests, take the first unblocked")))
