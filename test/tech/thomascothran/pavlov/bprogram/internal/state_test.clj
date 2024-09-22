(ns tech.thomascothran.pavlov.bprogram.internal.state-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.event.defaults]
            [tech.thomascothran.pavlov.bid.defaults]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram.internal.state :as s]))

#_(def example-data-structure
    {:waits {:event-a #{:bthread-a
                        :bthread-b}}
     :requests {:event-b #{:bthread-c
                           :bthread-d}}
     :blocks {:event-c #{:bthread-a}}

     ;; duplicate for lookup
     :bthreads->bid {:bthread-a {:waiting-on #{:event-a}}
                     :bthread-b {:waiting-on #{:event-a}
                                 :block #{:event-c}}
                     :bthread-c {:request #{:event-b}}
                     :bthread-d {:request #{:event-b}}}})

(deftest test-bidmap
  (let [bthread->bid
        {{:priority 1} {:priority 1}
         {:priority 10} {:priority 10}
         {:priority 6} {:priority 10}}

        bidmap
        (into (s/make-bid-map) bthread->bid)]
    (is (= {:priority 10}
           (ffirst (seq bidmap))))
    (is (= bthread->bid bidmap)))

  (let [bid-a {:request #{{:type :a}} :priority 0}
        bid-b {:wait-on #{{:type :b}} :priority 3}
        bid-c {:block   #{{:type :c}} :priority 0}

        bthread->bid {bid-a bid-a
                      bid-b bid-b
                      bid-c bid-c}
        bidmap
        (into (s/make-bid-map) bthread->bid)]

    (is (= 3 (count bidmap)))))

(deftest test-init
  (let [bid-a {:request #{{:type :a}} :priority 0}
        bid-b {:wait-on #{{:type :b}} :priority 3}
        bid-c {:block   #{{:type :c}} :priority 1}
        bthreads [bid-a bid-b bid-c]

        expected-bids  ;; Literals are both
        {bid-a bid-a   ;; bthread and bids
         bid-b bid-b
         bid-c bid-c}

        state (s/init bthreads)]

    (is (= {:a #{bid-a}}
           (:requests state)))

    (is (= {:b #{bid-b}}
           (:waits state)))

    (is (= {:c #{bid-c}}
           (:blocks state)))

    (is (nil? (:last-event state)))

    (is (= 3 (count (:bthread->bid state))))

    (is (sorted? (:bthread->bid state)))

    (is (= expected-bids
           (into {} (:bthread->bid state)))
        "Should have the expected bids")))

(deftest test-blocked
  (let [bthread-a {:request #{{:type :a}} :block #{:d}}
        bthread-b {:request #{{:type :b}} :priority 10
                   :block #{:c}}
        state (s/init [bthread-a bthread-b])]
    (is (= #{:c :d} (s/blocked state)))))

(deftest test-winning-bid
  (let [bthread-a {:request #{{:type :a}}}
        state (s/init [bthread-a])]
    (is (= {:type :a}
           (s/next-event state))))

  (let [bthread-a {:request #{{:type :a}}}
        bthread-b {:request #{{:type :b}} :priority 10}
        bthread-c {:request #{{:type :c}} :priority 5}
        state (s/init [bthread-a bthread-b bthread-c])]

    (is (= {:type :b}
           (s/next-event state))))

  (let [bthread-a {:request #{{:type :a}}}
        bthread-b {:request #{{:type :b}} :priority 10}
        bthread-c {:request #{{:type :c}}
                   :priority 5
                   :block #{:b}}
        state (s/init [bthread-a bthread-b bthread-c])]
    (is (= {:type :c}
           (s/next-event state)))))

(deftest test-notify-bthreads!
  (let [request-bthread-ab (b/seq [{:request #{:a}}
                                   {:request #{:b}}])
        request-bthread-c {:request #{:c}}
        wait-bthread-d (b/seq [{:wait-on #{:a}}
                               {:request #{:d}}])

        state (s/init [request-bthread-ab
                       request-bthread-c
                       wait-bthread-d])

        result
        (s/notify-bthreads! state {:type :a})]

    (is (= {request-bthread-ab {:request #{:b}}
            wait-bthread-d {:request #{:d}}}
           (:bthreads->bids result)))

    (is (= {:d #{wait-bthread-d}
            :b #{request-bthread-ab}}
           (:requests result)))))

(deftest test-step-removes-requests
  (let [bthread-a (b/seq [{:request #{:a}}])
        state (s/init [bthread-a])
        next-state (s/step state {:type :a})]
    (is (= #{}
           (get-in next-state [:requests :a])))))

(deftest test-step-removes-terminated-bthreads
  (let [bthread-a (b/seq [{:request #{:a}}])
        state (s/init [bthread-a])
        next-state (s/step state {:type :a})]
    (is (nil? (get-in next-state [:bthread->bid bthread-a])))))

(deftest test-step
  (let [bid-a {:request #{:a} :priority 1}
        bid-b {:request #{:b} :priority 0}
        state (s/init [bid-a bid-b])
        next-state (s/step state nil)]
    (is (= :a (:next-event next-state)))))


