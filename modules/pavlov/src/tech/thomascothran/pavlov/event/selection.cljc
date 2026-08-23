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
            [tech.thomascothran.pavlov.event :as event]))

(defn- blocked
  [bthread->bids]
  (into #{}
        (comp (map second)
              (mapcat bid/block)
              (map event/type))
        bthread->bids))

(defn- requested-event-unblocked?
  [blocked-event-types requested-event]
  (not (contains? blocked-event-types (event/type requested-event))))

(defn- unblocked?
  [blocked-event-types bid]
  (boolean
   (some #(requested-event-unblocked? blocked-event-types %)
         (bid/request bid))))

(defn- first-unblocked-event
  "Return a one-element marker containing the first unblocked event.

  The marker distinguishes a requested nil/false event from no matching event
  and lets prioritized-event stop without materializing candidate collections."
  [blocked-event-types request]
  (reduce (fn [_ requested-event]
            (when (requested-event-unblocked? blocked-event-types
                                              requested-event)
              (reduced [requested-event])))
          nil
          request))

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
   (let [unblocked-bid
         (fn [bthread-name]
           (let [bid (get bthread->bid bthread-name)]
             (when (unblocked? blocked-event-types bid)
               bid)))]
     (if (set? bthreads-by-priority)
       (into [] (keep unblocked-bid) bthreads-by-priority)
       (if-some [bid (some unblocked-bid bthreads-by-priority)]
         [bid]
         [])))))

(defn- prioritized-events-from-request
  [blocked-event-types request]
  (let [unblocked-events (remove #(contains? blocked-event-types
                                              (event/type %))
                                 request)]
    (if (set? request)
      unblocked-events
      (take 1 unblocked-events))))

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
               (mapcat #(prioritized-events-from-request blocked-event-types %)))
         (prioritized-bids bthreads-by-priority
                           bthread->bid
                           blocked-event-types))))

(defn prioritized-event
  ([bthreads-by-priority bthread->bid]
   (prioritized-event bthreads-by-priority
                      bthread->bid
                      (blocked bthread->bid)))
  ([bthreads-by-priority bthread->bid blocked-event-types]
   (some-> (some (fn [bthread-name]
                   (some->> (get bthread->bid bthread-name)
                            bid/request
                            (first-unblocked-event blocked-event-types)))
                 bthreads-by-priority)
           first)))
