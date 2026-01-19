(ns tech.thomascothran.pavlov.bprogram.state
  (:require [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.event.selection
             :as event-selection]
            [tech.thomascothran.pavlov.bprogram.notification :as notification]))

(defn next-event
  "The winning bid will request a new event"
  [state]
  (let [bthreads-by-priority (get state :bthreads-by-priority)
        bthreads->bid (get state :bthread->bid)]
    (event-selection/prioritized-event
     bthreads-by-priority
     bthreads->bid)))

(defn- merge-event->bthreads
  [previous new]
  (merge-with #(into (or %1 #{}) %2)
              previous new))

(defn- splice-bthread-priorities
  "Given an existing list of bthread priorities,
  add the child bthreads in directly after their
  parents."
  [bthread-priorities parent->child-bthreads]
  (reduce (fn [acc curr-bthread-name]
            (if-let [child-bthread-names
                     (get parent->child-bthreads
                          curr-bthread-name)]
              (into acc (into [curr-bthread-name] child-bthread-names))
              (conj acc curr-bthread-name)))
          []
          bthread-priorities))

;; Here, we can put the bthreads in order of priority
(defn- merge-notification-results
  [state {:keys [bthread->bid waits requests blocks]}]
  (-> state
      (update :bthread->bid merge bthread->bid)
      (update :waits merge-event->bthreads waits)
      (update :requests merge-event->bthreads requests)
      (update :blocks merge-event->bthreads blocks)))

(defn- initialize-spawned-bthreads
  [state spawned-bthreads parent->child-bthreads]
  (loop [state state
         spawned-bthreads spawned-bthreads
         parent->child-bthreads parent->child-bthreads]
    (if (seq spawned-bthreads)
      (let [notification-results (notification/notify-bthreads!
                                  {:name->bthread spawned-bthreads})
            next-spawned-bthreads (get notification-results :bthreads)
            next-parent->child-bthreads (get notification-results
                                             :parent->child-bthreads)
            notification-results (dissoc notification-results
                                         :bthreads
                                         :parent->child-bthreads)
            state (-> state
                      (update :name->bthread merge spawned-bthreads)
                      (update :bthreads-by-priority splice-bthread-priorities
                              parent->child-bthreads)
                      (merge-notification-results notification-results))]
        (recur state next-spawned-bthreads next-parent->child-bthreads))
      state)))

(defn init
  "Initiate the state"
  [named-bthreads]
  (let [ordered-bthreads? (not (map? named-bthreads))
        name->bthread (into {} named-bthreads)
        bthreads-by-priority
        (if ordered-bthreads?
          (into [] (map first) named-bthreads)
          (into #{} (map first) named-bthreads))

        initial-state {:bthread->bid {}
                       :last-event nil
                       :name->bthread name->bthread
                       :bthreads-by-priority bthreads-by-priority}

        notification-results (notification/notify-bthreads! initial-state)
        pending-bthreads (get notification-results :bthreads)
        pending-parent->child-bthreads (get notification-results
                                            :parent->child-bthreads)
        state (merge initial-state
                     (dissoc notification-results
                             :bthreads
                             :parent->child-bthreads))
        state (if (seq pending-bthreads)
                (initialize-spawned-bthreads state
                                             pending-bthreads
                                             pending-parent->child-bthreads)
                state)
        next-event' (next-event state)]
    (assoc state :next-event next-event')))

(defn- remove-triggered-bthreads
  [triggered-bthreads event->threads]
  (into {}
        (map (fn [[event bthreads]]
               [event (->> bthreads
                           (remove (or triggered-bthreads #{}))
                           (into #{}))]))
        event->threads))

(defn- initialize-new-bthreads!
  [name->bthread]
  (notification/notify-bthreads! {:name->bthread name->bthread}))

(defn next-state
  [{:keys [state event]}
   {new-bthread->bid :bthread->bid
    new-waits :waits
    new-requests :requests
    new-blocks :blocks
    new-bthreads :bthreads
    :keys [parent->child-bthreads]}]
  (let [triggered-bthreads
        (into #{}
              (mapcat #(get % (event/type event)))
              [(get state :waits)
               (get state :requests)])

        rm-triggered-bthreads
        #(remove-triggered-bthreads triggered-bthreads %)

        new-bthread-bids (when new-bthreads
                           (initialize-new-bthreads! new-bthreads))
        new-bthread-waits (get new-bthread-bids :waits)
        new-bthread-requests (get new-bthread-bids :requests)
        new-bthread-blocks (get new-bthread-bids :blocks)

        waits (-> (get state :waits)
                  (dissoc event)
                  rm-triggered-bthreads
                  (merge-event->bthreads new-waits)
                  (cond-> (seq new-bthread-waits)
                    (merge-event->bthreads new-bthread-waits)))
        requests (-> (get state :requests)
                     (dissoc event)
                     rm-triggered-bthreads
                     (merge-event->bthreads new-requests)
                     (cond-> (seq new-bthread-requests)
                       (merge-event->bthreads new-bthread-requests)))
        blocks (-> (get state :blocks)
                   (dissoc event)
                   rm-triggered-bthreads
                   (merge-event->bthreads new-blocks)
                   (cond-> (seq new-bthread-blocks)
                     (merge-event->bthreads new-bthread-blocks)))

        next-bthread->bid
        (-> (get state :bthread->bid)
            (#(apply dissoc % triggered-bthreads))
            (into new-bthread->bid)
            (cond-> new-bthreads
              (merge (get new-bthread-bids :bthread->bid))))

        next-bthread-priorities
        (if new-bthreads
          (splice-bthread-priorities (get state :bthreads-by-priority)
                                     parent->child-bthreads)
          (get state :bthreads-by-priority))

        next-state
        (assoc state
               :last-event event
               :waits waits
               :requests requests
               :blocks blocks
               :bthreads-by-priority next-bthread-priorities
               :bthread->bid next-bthread->bid
               :name->bthread (merge (get state :name->bthread)
                                     (into {} new-bthreads)))

        next-event (next-event next-state)]

    (assoc next-state :next-event next-event)))

(defn step
  "Return the next state based on the event"
  [state event]
  (let [last-event (get state :last-event)
        pending-bthreads (get state :pending-bthreads)
        pending-parent->child-bthreads (get state :pending-parent->child-bthreads)
        notification-results
        (notification/notify-bthreads! state event)
        notification-results
        (cond-> notification-results
          (seq pending-bthreads)
          (update :bthreads merge pending-bthreads)

          (seq pending-parent->child-bthreads)
          (update :parent->child-bthreads
                  (fn [parent->child]
                    (merge-with into
                                (or parent->child {})
                                pending-parent->child-bthreads))))
        next-state
        (next-state {:state state
                     :last-event last-event
                     :event event}
                    notification-results)]
    (cond-> next-state
      (seq pending-bthreads)
      (dissoc :pending-bthreads :pending-parent->child-bthreads))))
