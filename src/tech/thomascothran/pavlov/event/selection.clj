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

  `prioritized-bid` implements this strategy.


  It is also useful to select bids randomly, or according to some other
  selection criteria. Each bid selectoin strategy takes a sequence of
  bthreads and a map of a bthread to its bid.

  request selection strategies
  -----------------------------
  The winning bid may request multiple events and some of these events
  may be blocked. Thus there is a decision to be made about which of a
  bid's unblocked requests should be selected.

  `prioritized-event` implements a strategy where the bids are part of an
  ordered sequence, from highest to lowest priority. The highest priority
  event is selected. "
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

(defn prioritized-bid
  "Returns the first bthread that has an unblocked request,
  ordered by priority"
  ([bthreads-by-priority bthread->bid]
   (prioritized-bid bthreads-by-priority
                    bthread->bid
                    (blocked bthread->bid)))
  ([bthreads-by-priority bthread->bid blocked-event-types]
   (->> (filter (unblocked-bthread? bthread->bid blocked-event-types)
                bthreads-by-priority)
        first
        (get bthread->bid))))

;; ===========================|
;; event selection strategies |
;; ===========================|

(defn prioritized-event
  [bthreads-by-priority bthread->bid]
  (let [blocked-event-types (blocked bthread->bid)]
    (some->> (prioritized-bid bthreads-by-priority bthread->bid)
             bid/request
             (remove (comp blocked-event-types event/type))
             first)))
