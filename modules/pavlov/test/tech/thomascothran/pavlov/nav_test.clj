(ns tech.thomascothran.pavlov.nav-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.nav :as pnav]))

(defn make-test-bthreads
  []
  {:letters (b/bids [{:request [:a]}
                     {:request [:b]}
                     {:request [:c]}])
   :numbers (b/bids [{:request #{1 2}}
                     {:request #{3}}])})

(defn make-test-linear-bthreads
  []
  {:linear (b/bids [{:request [:a]}
                    {:request [:b]}
                    {:request [:c]}])})

(deftest test-nav-to
  (testing "with event types"
    (let [root (pnav/root (make-test-bthreads))
          at-first-branch (pnav/to root 1)]
      (is (= 1 (e/type (:pavlov/event at-first-branch))))
      (is (:pavlov/branches at-first-branch))))

  (testing "with functions"
    (let [root (pnav/root (make-test-bthreads))
          at-first-branch (pnav/to root (comp (partial = 1)
                                              e/type))]
      (is (= 1 (e/type (:pavlov/event at-first-branch))))
      (is (:pavlov/branches at-first-branch)))))

(deftest test-follow
  (let [root (pnav/root (make-test-bthreads))
        followed (pnav/follow root [1])]
    (is (= 1 (e/type (:pavlov/event followed)))))

  (let [root (pnav/root (make-test-bthreads))
        followed (pnav/follow root [1 3])]
    (is (= 3 (e/type (:pavlov/event followed)))))

  (let [root (pnav/root (make-test-linear-bthreads))
        followed (pnav/follow root [:a :c])]
    (is (= :c (e/type (:pavlov/event followed)))))

  (let [root (pnav/root (make-test-linear-bthreads))
        followed (pnav/follow root [:a :b])]
    (is (= :b (e/type (:pavlov/event followed)))))

  (let [root (pnav/root (make-test-linear-bthreads))
        followed (pnav/follow root [:a :d])]
    (is (nil? (:pavlov/event followed)))))
