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

;; Here, we can put the bthreads in order of priority
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

        state (merge initial-state notification-results)
        next-event' (next-event state)]
    (assoc state :next-event next-event')))

(defn- merge-event->bthreads
  [previous new]
  (merge-with #(into (or %1 #{}) %2)
              previous new))

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

(defn- splice-bthread-priorities
  [bthread-priorities parent->child-bthreads])

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

        new-bthread-bids (initialize-new-bthreads! new-bthreads)
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

        _ (clojure.pprint/pprint {:event event
                                  :waits waits
                                  :requests requests
                                  :blocks blocks
                                  :new-bthreads new-bthreads
                                  :new-bthread-requests new-bthread-requests})

        next-bthread->bid
        (-> (get state :bthread->bid)
            (#(apply dissoc % triggered-bthreads))
            (into new-bthread->bid))

        next-state
        (assoc state
               :last-event event
               :waits waits
               :requests requests
               :blocks blocks
               :bthreads-by-priority (-> (get state :bthreads-by-priority)
                                         (into (map first new-bthreads)))
               :bthread->bid next-bthread->bid
               :name->bthread (merge (get state :name->bthread)
                                     (into {} new-bthreads)))

        next-event (next-event next-state)]
    (assoc next-state :next-event next-event)))

(defn step
  "Return the next state based on the event"
  [state event]
  (let [last-event (get state :last-event)
        notification-results
        (notification/notify-bthreads! state event)]
    (next-state {:state state
                 :last-event last-event
                 :event event}
                notification-results)))
