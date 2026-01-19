(ns tech.thomascothran.pavlov.bprogram.notification
  "Functions for notifying bthreads of events and collecting their bids."
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.event :as event]))

(defn bthreads-to-notify
  "Returns the set of bthread names that should be notified for a given event.

  A bthread should be notified if it is either waiting on or requesting
  the event type.

  Parameters:
  - state: The current state map containing :waits and :requests indices
  - event: The event to check (must have a type via event/type)

  Returns:
  A vector of bthread names that are interested in this event type.
  Returns an empty vector if event is nil."
  [state event]
  (when event
    (reduce into [] [(get-in state [:waits (event/type event)])
                     (get-in state [:requests (event/type event)])])))

(defn index-bid-events
  "Indexes events from a bid by adding the bthread to the appropriate event-type sets.

  Creates an inverted index that maps event types to sets of interested bthreads,
  enabling fast lookup of which bthreads should be notified when an event occurs.

  Parameters:
  - state: The current state map containing event indices
  - bthread-name: The name/key of the bthread whose events are being indexed
  - bid: The bid containing the events to index
  - request-type: One of :requests, :waits, or :blocks

  Returns:
  Updated state with the bthread-name added to the sets for each event type
  in the bid under the path [request-type event-type].

  Example:
  (index-bid-events {:requests {:click #{}}}
                    :my-bthread
                    {:request #{{:type :click} {:type :hover}}}
                    :requests)
  => {:requests {:click #{:my-bthread}
                 :hover #{:my-bthread}}}"
  [state bthread-name bid request-type]
  (let [event-fn (case request-type
                   :requests bid/request
                   :waits bid/wait-on
                   :blocks bid/block)
        event-types (event-fn bid)]
    (if (seq event-types)
      (reduce (fn [state requested-event]
                (update-in state [request-type
                                  (if :requests
                                    (event/type requested-event)
                                    requested-event)]
                           #(into #{bthread-name} %)))
              state
              event-types)
      state)))

(defn- spawn-only-bid?
  [bid]
  (and (seq (bid/bthreads bid))
       (empty? (bid/request bid))
       (empty? (bid/wait-on bid))
       (empty? (bid/block bid))))

(defn notify-bthreads!
  "Notifies relevant bthreads of an event and collects their updated bids.

  This function:
  1. Identifies which bthreads should be notified (those waiting on or requesting the event)
  2. Calls each bthread's bid function with the event to get their new bid
  3. Indexes the events from each new bid for fast lookup

  Parameters:
  - state: The current BP state containing:
    - :name->bthread - map of bthread names to bthread instances
    - :waits - index of event types to waiting bthreads
    - :requests - index of event types to requesting bthreads
  - event: The event that occurred (optional - if not provided, notifies ALL bthreads with nil event)

  Returns:
  A map containing only the updates from notified bthreads:
  - :bthread->bid - map of bthread names to their new bids
  - :requests - index of requested event types to bthread names
  - :waits - index of waited event types to bthread names
  - :blocks - index of blocked event types to bthread names

  The caller is responsible for merging these updates into the main state."
  ([state]
   (notify-bthreads! state nil (keys (:name->bthread state))))
  ([state event]
   (notify-bthreads! state event (bthreads-to-notify state event)))
  ([state event bthread-names]
   (reduce (fn [acc bthread-name]
             (let [bthread (get-in state [:name->bthread bthread-name])
                   _ (when-not bthread (println "No bthread found for" bthread-name))
                   bid (b/notify! bthread event)
                   bthreads (bid/bthreads bid)
                   spawn-only? (spawn-only-bid? bid)]
               (cond-> acc
                 (seq bthreads)
                 (-> (update :bthreads merge bthreads)
                     (assoc-in [:parent->child-bthreads bthread-name]
                               (into #{} (keys bthreads))))

                 (not spawn-only?)
                 (-> (assoc-in [:bthread->bid bthread-name] bid)
                     (index-bid-events bthread-name bid :requests)
                     (index-bid-events bthread-name bid :waits)
                     (index-bid-events bthread-name bid :blocks)))))
           {:bthread->bid {}}
           bthread-names)))
