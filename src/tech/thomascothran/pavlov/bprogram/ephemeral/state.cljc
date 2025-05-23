(ns tech.thomascothran.pavlov.bprogram.ephemeral.state
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.event :as event]
            [clojure.set :as set]))

(defn assoc-events
  [state bthread bid request-type]
  (let [event-fn (case request-type
                   :requests bid/request
                   :waits bid/wait-on
                   :blocks bid/block)
        event-types (event-fn bid)]
    (if (seq event-types)
      (reduce (fn [requests requested-event]
                (update-in requests [request-type
                                     (if :requests
                                       (event/type requested-event)
                                       requested-event)]
                           #(into #{bthread} %)))
              state
              event-types)
      state)))

(defn blocked
  [state]
  (into #{}
        (comp (map second)
              (mapcat bid/block)
              (map event/type))
        (get state :bthread->bid)))

(defn unblocked-requests
  [blocked-events bid]
  (set/difference (into #{}
                        (map event/type)
                        (bid/request bid))
                  blocked-events))

(defn unblocked?
  [blocked-events bid]
  (seq (unblocked-requests blocked-events bid)))

(defn next-event
  "The winning bid will request a new event"
  [state]
  (let [blocked-event-types (blocked state)
        bthreads-by-priority (get state :bthreads-by-priority)
        bthreads->bid (get state :bthread->bid)

        event
        (some->> bthreads-by-priority
                 (map #(get bthreads->bid %))
                 (filter #(unblocked? blocked-event-types %))
                 first
                 bid/request
                 (remove (comp blocked-event-types event/type))
                 first)]

    event))

(defn bthreads-to-notify
  "Given an event, return the bthreads to notify"
  [state event]
  (when event
    (reduce into [] [(get-in state [:waits (event/type event)])
                     (get-in state [:requests (event/type event)])])))

(defn notify-bthreads!
  "Notify the bthreads, returning a map of the

  - `bthread->bid`: only the new bids. The caller has
    to merge this into the bthreads to bids
  - `requests`: the new requests
  - `waits`: the new waits
  - `blocks`: the new blocks"
  [state event]
  (let [bthreads (bthreads-to-notify state event)]
    (reduce (fn [acc bthread]
              (let [bid (b/bid bthread event)]
                (-> acc
                    (assoc-in [:bthread->bid (b/name bthread)] bid)
                    (assoc-events bthread bid :requests)
                    (assoc-events bthread bid :waits)
                    (assoc-events bthread bid :blocks))))
            {:bthread->bid {}}
            bthreads)))

;; Here, we can put the bthreads in order of priority
(defn init
  "Initiate the state"
  [bthreads]
  (let [bid-set {}
        state
        (reduce (fn [acc bthread]
                  (let [bid (b/bid bthread nil)]
                    (-> acc
                        (update :bthread->bid into {(b/name bthread)
                                                    bid})
                        (assoc-events bthread bid :requests)
                        (assoc-events bthread bid :waits)
                        (assoc-events bthread bid :blocks))))
                {:bthread->bid bid-set
                 :last-event nil
                 :bthreads-by-priority (mapv #(b/name %) bthreads)}
                bthreads)
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

(defn next-state
  [{:keys [state event]}
   {new-bthread->bid :bthread->bid
    new-waits        :waits
    new-requests     :requests
    new-blocks       :blocks}]

  (let [triggered-bthreads
        (into #{}
              (mapcat #(get % (event/type event)))
              [(get state :waits)
               (get state :requests)])

        rm-triggered-bthreads
        #(remove-triggered-bthreads triggered-bthreads %)

        waits (-> (get state :waits)
                  (dissoc event)
                  rm-triggered-bthreads
                  (merge-event->bthreads new-waits))
        requests (-> (get state :requests)
                     (dissoc event)
                     rm-triggered-bthreads
                     (merge-event->bthreads new-requests))
        blocks   (-> (get state :blocks)
                     (dissoc event)
                     rm-triggered-bthreads
                     (merge-event->bthreads new-blocks))

        next-bthread->bid
        (-> (get state :bthread->bid)
            (#(apply dissoc % triggered-bthreads))
            (into new-bthread->bid))

        next-state
        {:last-event event
         :waits waits
         :requests requests
         :blocks blocks
         :bthreads-by-priority (get state :bthreads-by-priority)
         :bthread->bid next-bthread->bid}

        next-event (next-event next-state)]
    (assoc next-state :next-event next-event)))

(defn step
  "Return the next state based on the event"
  [state event]
  (let [last-event (get state :last-event)
        notification-results
        (notify-bthreads! state event)]
    (next-state {:state state
                 :last-event last-event
                 :event event}
                notification-results)))
