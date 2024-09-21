(ns tech.thomascothran.pavlov.bprogram.internal.state
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
                                     (event/type requested-event)]
                           #(into #{bthread} %)))
              state
              event-types)
      state)))

(defn- bthread-sorter
  [bthread-a bthread-b]
  (compare [(b/priority bthread-b) (hash bthread-b)]
           [(b/priority bthread-a) (hash bthread-a)]))

(defn make-bid-map
  []
  (sorted-map-by bthread-sorter))

(defn blocked
  [state]
  (into #{}
        (comp (map second)
              (mapcat bid/block))
        (:bthread->bid state)))

(defn next-event
  "The winning bid will request a new event"
  [state]
  (let [blocked-event-types (blocked state)]
    (->> (seq (:bthread->bid state))
         (remove #(= #{}
                     (set/difference
                      (into #{}
                            (map event/type)
                            (bid/request (second %)))
                      blocked-event-types)))
         first
         second
         bid/request
         (remove (comp blocked-event-types event/type))
         first)))

(defn bthreads-to-notify
  "Given an event, return the bthreads to notify"
  [state event]
  (when event
    (reduce into [] [(get-in state [:waits (event/type event)])
                     (get-in state [:requests (event/type event)])])))

(defn notify-bthreads!
  "Notify the bthreads, returning a map of the
 
  - `bthreads->bids`: only the new bids. The caller has
    to merge this into the bthreads to bids
  - `requests`: the new requests
  - `waits`: the new waits
  - `blocks`: the new blocks"
  [state event]
  (let [bthreads (bthreads-to-notify state event)]
    (reduce (fn [acc bthread]
              (let [bid (b/bid bthread)]
                (-> acc
                    (assoc-in [:bthreads->bids bthread] bid)
                    (assoc-events bthread bid :requests)
                    (assoc-events bthread bid :waits)
                    (assoc-events bthread bid :blocks))))
            {:bthreads->bids {}}
            bthreads)))

(defn init
  "Initiate the state"
  [bthreads]
  (let [bid-set (make-bid-map)]
    (reduce (fn [acc bthread]
              (let [bid (b/bid bthread)]
                (-> acc
                    (update :bthread->bid into {bthread bid})
                    (assoc-events bthread bid :requests)
                    (assoc-events bthread bid :waits)
                    (assoc-events bthread bid :blocks))))
            {:bthread->bid bid-set
             :last-event nil}
            bthreads)))

(merge-with #(into (or %1 #{}) %2)
            {:a #{:b :c}} {:a #{:d}})

(merge-with #(into (or %1 #{}) %2)
            {:a nil} {:a #{:d}})

(defn- merge-event->bthreads
  [previous new]
  (merge-with #(into (or %1 #{}) %2)
              previous new))

(defn step
  "Return the next state based on the event"
  [state event]
  (let [last-event event
        next-event' (next-event state)

        {new-bthread->bid :bthread->bid
         new-waits        :waits
         new-requests     :requests
         new-blocks       :blocks}
        (notify-bthreads! state last-event)

        bthread->bid (into (:bthread->bid state) new-bthread->bid)

        waits (-> (:waits state)
                  (dissoc last-event)
                  (merge-event->bthreads new-waits))
        requests (-> (:requests state)
                     (dissoc last-event)
                     (merge-event->bthreads new-requests))
        blocks   (-> (:blocks state)
                     (dissoc last-event)
                     (merge-event->bthreads new-blocks))]
    {:last-event event
     :next-event next-event'
     :waits waits
     :requests requests
     :blocks blocks
     :bthread->bid bthread->bid}))
