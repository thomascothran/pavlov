(ns tech.thomascothran.pavlov.event.selection
  "Contains strategies for select the next bid from a set of bthreads.

  Divides into three types of functions:

  `unblocked-bthreads?`
  ---------------------
  Returns a function to be applied to a a bthread to see if it is unblocked.

  This is a concrete function.

  Usage:

  ```clojure
  (defn unblocked-bthreads-by-priority
    [bthreads-by-priority bthread->bid blocked-event-types]
    (let [unblocked? (unblocked-bthread? bthread->bid blocked-event-types)]
      (filter unblocked? bthreads-by-priority)))
  ```

  bid selection strategies
  ------------------------
  There are different ways we can select winning bids. The simplest and
  easiest to think about is by priority, where every bthread has a unique
  priority order.

  If the bthreads are in an ordered collection, they are in priority order. If unordered they are all of equal priority

  request selection strategies
  -----------------------------
  The winning bid may request multiple events and some of these events
  may be blocked. Thus there is a decision to be made about which of a
  bid's unblocked requests should be selected.

  If the requests ordered sequence, they are priortized from highest
  to lowest priority. The highest priority event is selected. "
  (:require [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.event :as event]
            [clojure.set :as set]))

(defn- blocked
  [bthread->bids]
  (into #{}
        (comp (map second)
              (mapcat bid/block)
              (map event/type))
        bthread->bids))

(defn- unblocked-requests
  [blocked-events bid]
  (set/difference (into #{}
                        (map event/type)
                        (bid/request bid))
                  blocked-events))
(defn- unblocked?
  [blocked-events bid]
  (seq (unblocked-requests blocked-events bid)))

;; API functions

(defn unblocked-bthread?
  "Returns a function that checks if a bthread is unblocked"
  [bthread->bid blocked-event-types]
  (comp
   #(unblocked? blocked-event-types %)
   #(get bthread->bid %)))

;; =============================|
;; bthread selection strategies |
;; =============================|

(defn prioritized-bids
  "Returns the all bids which can be selected."

  ([bthreads-by-priority bthread->bid]
   (prioritized-bids bthreads-by-priority
                     bthread->bid
                     (blocked bthread->bid)))

  ([bthreads-by-priority bthread->bid blocked-event-types]
   (cond->> (into []
                  (comp (filter (unblocked-bthread? bthread->bid
                                                    blocked-event-types))
                        (map #(get bthread->bid %)))
                  bthreads-by-priority)
     (not (set? bthreads-by-priority))
     (take 1))))

(defn- prioritized-events-from-request
  [request]
  (if (set? request)
    request
    (take 1 request)))

;; ===========================|
;; event selection strategies |
;; ===========================|

(defn prioritized-events
  ([bthreads-by-priority bthread->bid]
   (prioritized-events bthreads-by-priority
                       bthread->bid
                       (blocked bthread->bid)))
  ([bthreads-by-priority bthread->bid blocked-event-types]
   (into []
         (comp (map bid/request)
               (mapcat prioritized-events-from-request)
               (remove (comp blocked-event-types event/type)))
         (prioritized-bids bthreads-by-priority
                           bthread->bid
                           blocked-event-types))))

(defn prioritized-event
  [bthreads-by-priority bthread->bid]
  (let [blocked-event-types (blocked bthread->bid)]
    (some->> (prioritized-bids bthreads-by-priority bthread->bid)
             first
             bid/request
             (remove (comp blocked-event-types event/type))
             first)))
