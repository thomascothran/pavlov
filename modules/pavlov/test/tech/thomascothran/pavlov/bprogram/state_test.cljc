(ns tech.thomascothran.pavlov.bprogram.state-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [tech.thomascothran.pavlov.event.defaults]
            [tech.thomascothran.pavlov.bid.defaults]
            [tech.thomascothran.pavlov.bthread.defaults]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.event.selection :as selection]
            [tech.thomascothran.pavlov.bprogram.state :as s]
            [tech.thomascothran.pavlov.bprogram.notification :as notification]))

(defn indexed-bthreads
  [event->bthreads]
  (into #{} (mapcat val) event->bthreads))

(defrecord CustomBid [requested waited blocked]
  bid/Bid
  (request [_] requested)
  (wait-on [_] waited)
  (block [_] blocked)
  (bthreads [_] nil)
  (hot [_] nil))

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

    (is (= #?(:squint {:a #{:bid-a}}
              :default {{:type :a} #{:bid-a}})
           (:requests state))
        "Requests are indexed by the requested event (by type on squint)")

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
        (notification/notify-bthreads! state :a)]

    (is (= {:request-bthread-ab {:request #{:b}}
            :wait-bthread-d {:request #{:d}}}
           (:bthread->bid result)))

    (is (= {:d #{:wait-bthread-d}
            :b #{:request-bthread-ab}}
           (:requests result)))))

(deftest test-step-removes-requests
  (let [bthread-a (b/bids [{:request #{:a}}])
        state (s/init [[:bthread-a bthread-a]])
        next-state (s/step state :a)]
    (is (not (contains? (:requests next-state) :a))
        "Empty event memberships should be removed."))

  (let [bthread-a (b/bids [{:request #{:a}}])
        bthread-b (b/bids [{:wait-on #{:a}}
                           {:request #{:b}}])
        state (s/init [[:bthread-a bthread-a]
                       [:bthread-b bthread-b]])
        next-state (s/step state :a)]
    (is (not (identical? bthread-a bthread-b)))
    (is (not (contains? (:requests next-state) :a)))
    (is (= #{:bthread-b}
           (get-in next-state [:requests :b])))))

(deftest incremental-step-updates-only-old-bid-event-keys
  (let [worker (b/bids [{:request [:go :shared]
                         :wait-on #{:old-wait}
                         :block #{:old-block}}
                        {:request [:new-request]
                         :wait-on #{:new-wait}
                         :block #{:new-block}}])
        state (s/init [[:worker worker]
                       [:shared-requester {:request [:shared]}]])
        next-state (s/step state :go)]
    (is (= #{:shared-requester} (get-in next-state [:requests :shared])))
    (is (not (contains? (:waits next-state) :old-wait)))
    (is (not (contains? (:blocks next-state) :old-block)))
    (is (= #{:worker} (get-in next-state [:requests :new-request])))
    (is (= #{:worker} (get-in next-state [:waits :new-wait])))
    (is (= #{:worker} (get-in next-state [:blocks :new-block])))))

(deftest unaffected-event-indexes-remain-structurally-shared
  (let [state (s/init [[:driver {:request [:tick]}]
                       [:parked {:wait-on #{:unrelated}}]
                       [:blocker {:block #{:blocked}}]])
        next-state (s/step state :tick)]
    (is (identical? (:waits state) (:waits next-state)))
    (is (identical? (:blocks state) (:blocks next-state)))))

(deftest unchanged-bids-keep-their-index-entries
  (let [observer (b/step (fn [_ _] [nil {:wait-on #{:a :b} :block #{:x}}]))
        ticker (b/step (fn [_ _] [nil {:request #{:a} :wait-on #{:b}}]))
        state (s/init [[:observer observer]
                       [:ticker ticker]])
        next-state (s/step state :a)]
    (is (= (select-keys state [:bthread->bid :waits :requests :blocks])
           (select-keys next-state [:bthread->bid :waits :requests :blocks]))
        "Equal bids leave every index unchanged")
    (is (= #{:observer} (get-in next-state [:waits :a]))
        "The triggered event type's key is rebuilt for unchanged bids")
    (is (= #{:ticker} (get-in next-state [:requests :a])))
    (is (identical? (get-in state [:waits :b]) (get-in next-state [:waits :b]))
        "Other memberships of unchanged bids are not rebuilt")
    (is (identical? (:blocks state) (:blocks next-state)))))

(deftest custom-bid-implementations-remain-indexable
  (let [custom-bid (->CustomBid [:go] [:watched] [:blocked])
        state (s/init [[:custom (b/step (fn [_ _] [nil custom-bid]))]])
        next-state (s/step state :go)]
    (is (= #{:custom} (get-in next-state [:requests :go])))
    (is (= #{:custom} (get-in next-state [:waits :watched])))
    (is (= #{:custom} (get-in next-state [:blocks :blocked])))))

(defn- order [entree]
  {:type :order-food :entree entree})

(defn- customer [entree]
  (b/bids [{:request #{(order entree)}}
           {:request #{{:type :served :entree entree}}}]))

(defn- recording-bthread
  "A bthread that keeps the same bid and records every event it is notified of."
  [!seen bid]
  (b/step (fn [_ event]
            (when event (swap! !seen conj event))
            [nil bid])))

(defn- run-events
  [state]
  (loop [state state
         events []]
    (if-some [event (:next-event state)]
      (recur (s/step state event) (conj events event))
      events)))

(deftest same-type-request-is-not-notified-by-a-different-event
  (let [init-state #(s/init [[:alice (customer :steak)]
                             [:bob (customer :vegan-salad)]])
        state (init-state)
        next-state (s/step state (order :steak))]
    (is (= (order :steak) (:next-event state)))
    (is (= {:request #{{:type :served :entree :steak}}}
           (get-in next-state [:bthread->bid :alice]))
        "The bthread whose request was selected advances")
    (is (= {:request #{(order :vegan-salad)}}
           (get-in next-state [:bthread->bid :bob]))
        "A same-type request with different data does not advance")
    (is (= [(order :steak)
            {:type :served :entree :steak}
            (order :vegan-salad)
            {:type :served :entree :vegan-salad}]
           (run-events (init-state)))
        "Both orders are placed and served")))

(deftest equal-requests-are-all-notified
  (let [state (s/init [[:alice (customer :steak)]
                       [:carol (customer :steak)]])
        next-state (s/step state (order :steak))]
    (is (= {:request #{{:type :served :entree :steak}}}
           (get-in next-state [:bthread->bid :alice])
           (get-in next-state [:bthread->bid :carol])))))

(deftest bthread-requesting-several-same-type-events-is-notified-once
  (let [!seen (atom [])
        indecisive (b/step (fn [_ event]
                             (when event (swap! !seen conj event))
                             [nil (if event
                                    {:request #{:done}}
                                    {:request #{(order :steak)
                                                (order :vegan-salad)}})]))
        state (s/init [[:indecisive indecisive]])
        next-state (s/step state (order :steak))]
    (is (= [(order :steak)] @!seen))
    (is (= {:done #{:indecisive}}
           (:requests next-state))
        "Every old request entry is removed from the index")))

(deftest bare-type-and-map-requests-are-different-events
  (let [bids-after (fn [event]
                     (-> (s/init [[:keyword-requester (b/bids [{:request #{:x}}
                                                               {:request #{:after-keyword}}])]
                                  [:map-requester (b/bids [{:request #{{:type :x}}}
                                                           {:request #{:after-map}}])]])
                         (s/step event)
                         :bthread->bid))]
    (is (= {:keyword-requester {:request #{:after-keyword}}
            :map-requester {:request #{{:type :x}}}}
           (bids-after :x))
        "`:x` does not satisfy a request for `{:type :x}`")
    (is (= {:keyword-requester {:request #{:x}}
            :map-requester {:request #{:after-map}}}
           (bids-after {:type :x}))
        "`{:type :x}` does not satisfy a request for `:x`")))

(deftest waits-match-every-event-of-the-type
  (let [!type-seen (atom [])
        !data-seen (atom [])
        state (s/init [[:type-auditor (recording-bthread !type-seen {:wait-on #{:order-food}})]
                       [:data-auditor (recording-bthread !data-seen {:wait-on #{(order :steak)}})]
                       [:alice (customer :steak)]
                       [:bob (customer :vegan-salad)]])]
    (run-events state)
    (is (= [(order :steak) (order :vegan-salad)] @!type-seen))
    (is (= [(order :steak) (order :vegan-salad)] @!data-seen)
        "A data-bearing wait entry stands for its whole event type")))

(deftest blocks-match-every-event-of-the-type
  (is (= [:done]
         (run-events (s/init [[:no-orders {:block #{:order-food}}]
                              [:bob (customer :vegan-salad)]
                              [:fallback (b/bids [{:request #{:done}}])]]))))
  (is (= [:done]
         (run-events (s/init [[:no-steak {:block #{(order :steak)}}]
                              [:bob (customer :vegan-salad)]
                              [:fallback (b/bids [{:request #{:done}}])]])))
      "A data-bearing block entry blocks its whole event type"))

(deftest unchanged-request-bid-keeps-its-index-entries
  (let [repeater (b/step (fn [_ _] [nil {:request #{(order :steak)}}]))
        state (s/init [[:repeater repeater]
                       [:bob (customer :vegan-salad)]])
        next-state (s/step state (order :steak))]
    (is (= (:requests state) (:requests next-state)))
    (is (= #{:repeater :bob} (indexed-bthreads (:requests next-state))))))

(defn- rebuilt-indexes
  "Build the event indexes from scratch for the bids currently in `state`."
  [state]
  (-> (s/init (into [] (:bthread->bid state)))
      (select-keys [:requests :waits :blocks])))

(defn- indexes
  [state]
  (select-keys state [:requests :waits :blocks]))

(deftest unsatisfied-same-type-requesters-stay-indexed
  (let [state (s/init [[:alice (customer :steak)]
                       [:bob (customer :vegan-salad)]
                       [:carol (customer :vegan-salad)]])
        next-state (s/step state (order :steak))]
    (is (= #{:alice :bob :carol} (indexed-bthreads (:requests next-state))))
    (is (= (rebuilt-indexes next-state) (indexes next-state)))
    (is (= #{:bob :carol}
           (get-in next-state [:requests #?(:squint :order-food
                                            :default (order :vegan-salad))]))
        "Unsatisfied requesters stay indexed (under their type on squint)")))

(deftest same-type-wait-wakes-a-requester-for-a-different-event
  (let [bob (b/bids [{:request #{(order :vegan-salad)}
                      :wait-on #{:order-food}}
                     {:request #{:bob-gives-up}}])
        state (s/init [[:alice (customer :steak)]
                       [:bob bob]])
        next-state (s/step state (order :steak))]
    (is (= {:request #{:bob-gives-up}}
           (get-in next-state [:bthread->bid :bob]))
        "Bob's type-level wait is triggered by Alice's order")
    (is (= (rebuilt-indexes next-state) (indexes next-state))
        "Bob's old request entry is removed even though his request was not selected")))

(deftest spawned-children-are-notified-by-their-requested-event
  (let [parent (b/bids [{:bthreads {:alice (customer :steak)
                                    :bob (customer :vegan-salad)}}])
        state (s/init [[:parent parent]])
        next-state (s/step state (order :vegan-salad))]
    (is (= {:request #{(order :steak)}}
           (get-in next-state [:bthread->bid :alice])))
    (is (= {:request #{{:type :served :entree :vegan-salad}}}
           (get-in next-state [:bthread->bid :bob])))
    (is (= (rebuilt-indexes next-state) (indexes next-state)))))

(deftest retired-requesters-leave-same-type-requesters-indexed
  (let [state (s/init [[:alice (b/bids [{:request #{(order :steak)}}])]
                       [:bob (customer :vegan-salad)]])
        next-state (s/step state (order :steak))]
    (is (not (contains? (:bthread->bid next-state) :alice)))
    (is (= {:request #{(order :vegan-salad)}}
           (get-in next-state [:bthread->bid :bob])))
    (is (= (rebuilt-indexes next-state) (indexes next-state)))))

(deftest replaced-requester-loses-its-data-bearing-request
  (let [replacement (b/bids [{:request #{(order :vegan-salad)}}])
        state (s/init [[:manager (b/bids [{:request #{:change-order}}
                                          {:bthreads {:worker replacement}}])]
                       [:worker {:request #{(order :steak)}}]])
        next-state (s/step state :change-order)]
    (is (identical? replacement (get-in next-state [:name->bthread :worker])))
    (is (= {:request #{(order :vegan-salad)}}
           (get-in next-state [:bthread->bid :worker])))
    (is (= (rebuilt-indexes next-state) (indexes next-state)))
    (is (= (order :vegan-salad) (:next-event next-state)))))

(deftest test-step-removes-terminated-bthreads
  (let [bthread-a (b/bids [{:request #{:a}}])
        state (s/init [[:bthread-a bthread-a]])
        next-state (s/step state :a)]
    (is (contains? (:bthread->bid state) :bthread-a))
    (is (not (contains? (:bthread->bid next-state) :bthread-a)))
    (is (not (contains? (:name->bthread next-state) :bthread-a)))
    (is (empty? (:requests next-state)))))

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
        next-state (s/step state :go)]
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

(deftest respawning-a-child-name-removes-the-old-childs-index-entries
  (let [old-child {:wait-on #{:old-event}}
        new-child {:wait-on #{:new-event}}
        parent {:request [:tick]
                :bthreads {:child new-child}}
        state (s/init [[:parent parent]
                       [:child old-child]])]
    (is (not (contains? (:waits state) :old-event)))
    (is (= #{:child} (get-in state [:waits :new-event])))))

(deftest respawning-same-child-name-does-not-duplicate-priority-entry
  (let [first-child (b/bids [{:wait-on #{:go}}])
        second-child (b/bids [{:wait-on #{:go}}])
        parent (b/bids [{:request #{:tick}
                         :bthreads {:child first-child}}
                        {:request #{:tick}
                         :bthreads {:child second-child}}])
        state (s/init [[:parent parent]])
        next-state (s/step state :tick)]
    (is (identical? first-child (get-in state [:name->bthread :child])))
    (is (identical? second-child (get-in next-state [:name->bthread :child]))
        "The parent advanced and respawned the child")
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
  (let [bid-a (b/bids [{:request #{:a}}])
        bid-b (b/step (constantly [nil {:request #{:b}}]))
        state (s/init [[:bid-a bid-a]
                       [:bid-b bid-b]])
        next-state (s/step state :a)]
    (is (= :a (:next-event state)))
    (is (not (contains? (:bthread->bid next-state) :bid-a)))
    (is (= :b (:next-event next-state)))))

(deftest retiring-a-name-does-not-retire-its-replacement
  (doseq [ordered? [true false]]
    (let [replacement (b/bids [{:request #{:new-work}}])
          bthreads [[:spawner (b/bids [{:wait-on #{:replace}}
                                     {:bthreads {:worker replacement}}])]
                    [:worker (b/bids [{:wait-on #{:replace} :block #{:new-work}}])]]
          next-state (s/step (s/init (if ordered? bthreads (into {} bthreads))) :replace)]
      (is (= :new-work (:next-event next-state)))
      (is (identical? replacement (get-in next-state [:name->bthread :worker])))
      (is (= {:new-work #{:worker}} (:requests next-state)))
      (is (empty? (:blocks next-state)))
      (is (= #{:worker} (set (:bthreads-by-priority next-state)))))))

(deftest self-replacement-keeps-its-priority
  (doseq [startup? [true false]]
    (let [replacement (b/bids [{:request #{:new-work}}])
          spawn {:bthreads {:worker replacement}}
          initial (s/init [[:worker (b/bids (if startup? [spawn] [{:wait-on #{:replace}} spawn]))]
                               [:other {:request #{:other}}]])
          next-state (if startup? initial (s/step initial :replace))]
      (is (= :new-work (:next-event next-state)))
      (is (= [:worker :other] (:bthreads-by-priority next-state)))
      (is (identical? replacement (get-in next-state [:name->bthread :worker]))))))
