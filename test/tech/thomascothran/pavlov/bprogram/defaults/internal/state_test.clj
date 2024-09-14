(ns tech.thomascothran.pavlov.bprogram.defaults.internal.state-test
  (:require [clojure.test :refer [testing is deftest]]
            [tech.thomascothran.pavlov.bprogram.defaults.internal.state :as state]
            [tech.thomascothran.pavlov.bthread.defaults]
            [tech.thomascothran.pavlov.bprogram.defaults]
            [tech.thomascothran.pavlov.bid.defaults]))

(deftest test-new-waits
  (testing "Given that I have a bthread and a bid
    And the bid has wait
    Then I receive that bthread is associated with the event it is waiting on"
    (let [bthread {:wait-on #{:x}}
          bid     bthread
          bthread->bid {bthread bid}]
      (is (= {:x #{bthread}}
             (state/new-waits bthread->bid)))))

  (testing "Given that I have a bthread and a bid
    And the bid has a request
    Then I receive that bthread is associated with the event it is waiting on"
    (let [bthread {:request #{:y}}
          bid     bthread
          bthread->bid {bthread bid}]
      (is (= {:y #{bthread}}
             (state/new-waits bthread->bid)))))

  (testing "Given I have a bid has multiple waits and requests
    When I find the new waits
    All the waits and reqests should be accounted for"
    (let [bthread {:request #{:a :b :c}
                   :wait-on #{:x :y :z}}
          bid     bthread
          bthread->bid {bthread bid}]
      (is (= (zipmap [:a :b :c :x :y :z] (repeat #{bthread}))
             (state/new-waits bthread->bid)))))

  (testing "Given I have multiple bthread with waits
    When I find the new waits
    All the bthreads' waits are present"
    (let [bthread-a {:request #{:a}}
          bid-a     bthread-a
          bthread-b {:wait-on #{:b}}
          bid-b     bthread-b
          bthread->bid {bthread-a bid-a
                        bthread-b bid-b}]
      (is (= {:a #{bthread-a}
              :b #{bthread-b}}
             (state/new-waits bthread->bid)))))

  (testing "Given I have multiple bthread with the same waits
    When I find the new waits
    All the bthreads' waits are present"
    (let [bthread-a {:request #{:a} :block #{:c}}
          bid-a     bthread-a
          bthread-b {:wait-on #{:a} :block #{:d}}
          bid-b     bthread-b
          bthread->bid {bthread-a bid-a
                        bthread-b bid-b}]
      (is (= {:a #{bthread-a bthread-b}}
             (state/new-waits bthread->bid)))))

  (testing "Given that I have a bthread and a bid
    And the bid has no requests or waits
    When I find the new waits
    Then I receive nothing"
    (let [bthread {:block #{:y}}
          bid     bthread
          bthread->bid {bthread bid}]
      (is (= {}
             (state/new-waits bthread->bid))))))

(deftest test-remove-old-waits
  (testing "Given that we have a bthread registry and an event
    And we have selected the next event
    Then we remove all the bthreads waiting on this event"
    (let [bthread-registry {:a #{{:wait-on #{:x}}}
                            :b #{{:wait-on #{:y}}}}]
      (is (= (dissoc bthread-registry :a)
             (state/remove-old-waits bthread-registry :a)))))

  (testing "Given that we have a bthread registry and an event
    And we have selected the next event
    Then we remove all the activated bthreads waiting on other events"
    (let [bthread-a #{{:wait-on #{:x}}}
          bthread-registry {:a bthread-a
                            :b #{{:wait-on #{:y}}}
                            :c bthread-a}]
      (is (= {:b #{{:wait-on #{:y}}}, :c #{}}
             (state/remove-old-waits bthread-registry :a))))))

