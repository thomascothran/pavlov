(ns tech.thomascothran.pavlov.bprogram.ephemeral.state-test
  (:require #?(:clj [clojure.test :refer [deftest testing is run-tests]]
               :cljs [cljs.test :refer [deftest testing is run-tests]])
            [tech.thomascothran.pavlov.event.defaults]
            [tech.thomascothran.pavlov.bid.defaults]
            [tech.thomascothran.pavlov.bthread.defaults]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram.ephemeral.state :as s]))

(deftest test-init
  (let [bid-a {:request #{{:type :a}}}
        bid-b {:wait-on #{{:type :b}}}
        bid-c {:block #{{:type :c}}}

        expected-bids ;; Literals are both
        {:bid-a bid-a ;; bthread and bids
         :bid-b bid-b
         :bid-c bid-c}

        state (s/init [[:bid-a bid-a]
                       [:bid-b bid-b]
                       [:bid-c bid-c]])]

    (is (= {:a #{:bid-a}}
           (:requests state)))

    (is (= {:b #{:bid-b}}
           (:waits state)))

    (is (= {:c #{:bid-c}}
           (:blocks state)))

    (is (nil? (:last-event state)))

    (is (= 3 (count (:bthread->bid state))))

    (is (= expected-bids
           (into {} (:bthread->bid state)))
        "Should have the expected bids")
    (is (= {:type :a}
           (:next-event state))
        "Should queue up the next event")))

(deftest test-winning-bid
  (let [bthread-a {:request #{{:type :a}}}
        state (s/init {:bthread-a bthread-a})]
    (is (= {:type :a}
           (s/next-event state))))

  (let [bthread-a {:request #{{:type :a}}}
        bthread-b {:request #{{:type :b}}}
        bthread-c {:request #{{:type :c}}}
        state (s/init [[:bthread-b bthread-b]
                       [:bthread-c bthread-c]
                       [:bthread-a bthread-a]])]

    (is (= {:type :b}
           (s/next-event state))))

  (let [bthread-a {:request #{{:type :a}}}
        bthread-b {:request #{{:type :b}}}
        bthread-c {:request #{{:type :c}}
                   :block #{:b}}
        state (s/init [[:bthread-c bthread-c]
                       [:bthread-a bthread-a]
                       [:bthread-b bthread-b]])]
    (is (= {:type :c}
           (s/next-event state)))))

(deftest test-blocked-events-on-winning-bid
  (let [bthread-a {:request [:blocked :a]}
        bthread-b {:block #{:blocked}}
        state (s/init [[:bthread-a bthread-a]
                       [:bthread-b bthread-b]])]
    (is (= :a (s/next-event state)))))

(deftest test-notify-bthreads!
  (let [request-bthread-ab (b/bids [{:request #{:a}}
                                    {:request #{:b}}])
        request-bthread-c {:request #{:c}}
        wait-bthread-d (b/bids [{:wait-on #{:a}}
                                {:request #{:d}}])

        state (s/init [[:request-bthread-ab request-bthread-ab]
                       [:request-bthread-c request-bthread-c]
                       [:wait-bthread-d wait-bthread-d]])

        result
        (s/notify-bthreads! state {:type :a})]

    (is (= {:request-bthread-ab {:request #{:b}}
            :wait-bthread-d {:request #{:d}}}
           (:bthread->bid result)))

    (is (= {:d #{:wait-bthread-d}
            :b #{:request-bthread-ab}}
           (:requests result)))))

(deftest test-step-removes-requests
  (let [bthread-a (b/bids [{:request #{:a}}])
        state (s/init [[:bthread-a bthread-a]])
        next-state (s/step state {:type :a})]
    (is (= #{} (get-in next-state [:requests :a]))))

  (let [bthread-a (b/bids [{:request #{:a}}])
        bthread-b (b/bids [{:wait-on #{:a}}
                           {:request #{:b}}])
        state (s/init [[:bthread-a bthread-a]
                       [:bthread-b bthread-b]])
        next-state (s/step state {:type :a})]
    (is (not (= bthread-a bthread-b)))
    (is (= #{} (get-in next-state [:requests :a])))
    (is (= #{:bthread-b}
           (get-in next-state [:requests :b])))))

(deftest test-step-removes-terminated-bthreads
  (let [bthread-a (b/bids [{:request #{:a}}])
        state (s/init [[:bthread-a bthread-a]])
        next-state (s/step state {:type :a})]
    (is (nil? (get-in next-state [:bthread->bid bthread-a])))))

(deftest test-step
  (let [bid-a {:request #{:a}}
        bid-b (b/step (constantly {:request #{:b}}))
        state (s/init [[:bid-a bid-a]
                       [:bid-b bid-b]])
        next-state (s/step state {:type :a})]
    (is (= :a (:next-event next-state)))))
