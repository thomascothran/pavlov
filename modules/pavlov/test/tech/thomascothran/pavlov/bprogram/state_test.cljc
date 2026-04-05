(ns tech.thomascothran.pavlov.bprogram.state-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [tech.thomascothran.pavlov.event.defaults]
            [tech.thomascothran.pavlov.bid.defaults]
            [tech.thomascothran.pavlov.bthread.defaults]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.event.selection :as selection]
            [tech.thomascothran.pavlov.bprogram.state :as s]
            [tech.thomascothran.pavlov.bprogram.notification :as notification]))

(defn indexed-bthreads
  [event->bthreads]
  (into #{} (mapcat val) event->bthreads))

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
        (notification/notify-bthreads! state {:type :a})]

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

(deftest init-deregisters-bthread-that-returns-nil
  (let [state (s/init [[:worker (b/bids [nil])]])]
    (is (not (contains? (:name->bthread state) :worker)))
    (is (not-any? #{:worker} (:bthreads-by-priority state)))
    (is (not (contains? (:bthread->bid state) :worker)))
    (is (not (contains? (indexed-bthreads (:waits state)) :worker)))
    (is (not (contains? (indexed-bthreads (:requests state)) :worker)))
    (is (not (contains? (indexed-bthreads (:blocks state)) :worker)))
    (is (nil? (:next-event state)))))

(deftest step-deregisters-bthread-that-returns-nil-after-event
  (let [worker (b/bids [{:request #{:go}} nil])
        state (s/init [[:worker worker]])
        next-state (s/step state {:type :go})]
    (is (not (contains? (:name->bthread next-state) :worker)))
    (is (not-any? #{:worker} (:bthreads-by-priority next-state)))
    (is (not (contains? (:bthread->bid next-state) :worker)))
    (is (not (contains? (indexed-bthreads (:waits next-state)) :worker)))
    (is (not (contains? (indexed-bthreads (:requests next-state)) :worker)))
    (is (not (contains? (indexed-bthreads (:blocks next-state)) :worker)))))

(deftest init-deregisters-spawn-only-parent
  (let [parent (b/bids [{:bthreads {:child (b/bids [{:wait-on #{:go}}])}}])
        state (s/init [[:parent parent]])]
    (is (not (contains? (:name->bthread state) :parent)))
    (is (not-any? #{:parent} (:bthreads-by-priority state)))
    (is (not (contains? (:bthread->bid state) :parent)))
    (is (contains? (:name->bthread state) :child))
    (is (contains? (:bthread->bid state) :child))
    (is (contains? (indexed-bthreads (:waits state)) :child))))

(deftest step-deregisters-spawned-child-that-terminates
  (let [parent (b/bids [{:bthreads {:child (b/bids [{:wait-on #{:go}}
                                                    nil])}}])
        state (s/init [[:parent parent]])
        next-state (s/step state {:type :go})]
    (is (not (contains? (:name->bthread next-state) :child)))
    (is (not-any? #{:child} (:bthreads-by-priority next-state)))
    (is (not (contains? (:bthread->bid next-state) :child)))
    (is (not (contains? (indexed-bthreads (:waits next-state)) :child)))
    (is (not (contains? (indexed-bthreads (:requests next-state)) :child)))
    (is (not (contains? (indexed-bthreads (:blocks next-state)) :child)))))

(deftest respawning-same-child-name-does-not-duplicate-priority-entry
  (let [mk-child (fn [] (b/bids [{:wait-on #{:go}}]))
        parent (b/bids [{:request #{:tick}
                         :bthreads {:child (mk-child)}}
                        {:request #{:tick}
                         :bthreads {:child (mk-child)}}])
        state (s/init [[:parent parent]])
        next-state (s/step state {:type :tick})]
    (is (= 1
           (count (filter #{:child}
                          (:bthreads-by-priority next-state)))))))

(deftest spawned-child-preserves-equal-priority-for-map-bthreads
  (let [parent (b/bids [{:bthreads {:child (b/bids [{:request #{:child}}])}}])
        sibling (b/bids [{:request #{:sibling}}])
        state (s/init {:parent parent
                       :sibling sibling})]
    (is (set? (:bthreads-by-priority state))
        "Map input should remain equal-priority after spawned bthreads are inserted")
    (is (= #{:child :sibling}
           (into #{}
                 (map event/type)
                 (selection/prioritized-events (:bthreads-by-priority state)
                                               (:bthread->bid state))))
        "Spawned child and existing sibling should both remain selectable at equal priority")))

(deftest test-step
  (let [bid-a {:request #{:a}}
        bid-b (b/step (constantly {:request #{:b}}))
        state (s/init [[:bid-a bid-a]
                       [:bid-b bid-b]])
        next-state (s/step state {:type :a})]
    (is (= :a (:next-event next-state)))))
