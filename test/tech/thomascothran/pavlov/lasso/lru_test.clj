(ns tech.thomascothran.pavlov.lasso.lru-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.lasso.lru :as lru]
            [tech.thomascothran.pavlov.lasso.proto :as proto]))

(deftest detects-simple-repeat-and-period
  (testing "A, B, A -> duplicate with correct period"
    (let [det (lru/make-lru-detector 8)]
      (proto/begin! det)
      (is (nil? (proto/observe! det :A)))
      (is (nil? (proto/observe! det :B)))
      (let [hit (proto/observe! det :A)]
        (is (map? hit))
        (is (= true (:repeat? hit)))
        (is (= 2 (:period hit)))
        (is (= :A (:key hit))))
      (proto/end! det))))

(deftest evicts-least-recently-used
  (testing "Touching :A makes it MRU; inserting :C evicts LRU (:B)"
    (let [det (lru/make-lru-detector 2)]
      (proto/begin! det)
      ;; Fill up to capacity with A, B
      (is (nil? (proto/observe! det :A)))
      (is (nil? (proto/observe! det :B)))

      (let [hit (proto/observe! det :A)]
        (is (= true (:repeat? hit))
            "A is MRU, hence should get a hit"))
      (is (nil? (proto/observe! det :C))
          "Insert :C -> capacity exceeded, LRU (:B) should be evicted")
      ;; Now
      (is (nil? (proto/observe! det :B))
          "B was LRU and should have been evicted; seeing it again is not a repeat")
      (let [hit2 (proto/observe! det :C)]
        (is (= true (:repeat? hit2))
            ":C should be in the window and return a repeat"))
      (proto/end! det))))
