(ns tech.thomascothran.pavlov.bprogram.notification
  "Functions for notifying bthreads of events and collecting their bids."
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.event :as event]))

(defn index-key-fn
  "Return the function from a bid entry to its key in the `index` event index,
  where `index` is one of `:requests`, `:waits`, or `:blocks`.

  Requests are keyed by the requested event itself, so that two same-type
  requests carrying different data are distinguishable. Waits and blocks are
  keyed by event type.

  Squint maps are JS objects and cannot key a map by value, so squint keys
  requests by type as well and filters candidates in `requesting-bthreads`."
  [index]
  #?(:squint event/type
     :default (if (= index :requests) identity event/type)))

(defn requesting-bthreads
  "Return the set of bthreads that requested `event`.

  A request is satisfied only by an equal event (`=`), whatever its Event
  implementation. A request for `{:type :order-food :entree :steak}` is not
  satisfied by `{:type :order-food :entree :vegan-salad}`, and `:x` is not
  satisfied by `{:type :x}`. Waits and blocks are not affected: they match by
  event type.

  The `:requests` index is keyed by the requested event, so the lookup itself
  applies `=` (via `hash` and equality). On squint it is keyed by type, and
  the candidates are filtered with `=`."
  [state event]
  #?(:squint
     (let [bthread->bid (get state :bthread->bid)]
       (into #{}
             (filter (fn [bthread-name]
                       (some #(= % event)
                             (bid/request (get bthread->bid bthread-name)))))
             (get-in state [:requests (event/type event)])))
     :default
     (get (get state :requests) event)))

(defn bthreads-to-notify
  "Returns the set of bthread names that should be notified for a given event.

  A bthread should be notified if it waits on the event type, or if the event
  equals one of its requests (see `requesting-bthreads`).

  Parameters:
  - state: The current state map containing :waits and :requests indices
  - event: The event to check (must have a type via event/type)

  Returns:
  A vector of bthread names that are interested in this event.
  Returns nil if event is nil."
  [state event]
  (when event
    (let [event-type (event/type event)
          requesting-bthreads (requesting-bthreads state event)
          requesting? (if (set? requesting-bthreads)
                        requesting-bthreads
                        (set requesting-bthreads))]
      (-> []
          (into (remove #(contains? requesting? %))
                (get-in state [:waits event-type]))
          (into requesting-bthreads)))))

(defn index-bid-events
  "Indexes events from a bid by adding the bthread to the appropriate key sets.

  Creates an inverted index that maps keys to sets of interested bthreads,
  enabling fast lookup of which bthreads should be notified when an event occurs.
  The `:requests` index is keyed by the requested event itself; the `:waits`
  and `:blocks` indexes are keyed by event type (see `index-key-fn`).

  Parameters:
  - state: The current state map containing event indices
  - bthread-name: The name/key of the bthread whose events are being indexed
  - bid: The bid containing the events to index
  - request-type: One of :requests, :waits, or :blocks

  Returns:
  Updated state with the bthread-name added to the set for each event in the
  bid under the path [request-type ((index-key-fn request-type) event)].

  Example:
  (index-bid-events {}
                    :my-bthread
                    {:request #{{:type :click :id 1} :hover}}
                    :requests)
  => {:requests {{:type :click :id 1} #{:my-bthread}
                 :hover #{:my-bthread}}}

  (index-bid-events {}
                    :my-bthread
                    {:wait-on #{{:type :scroll :id 2}}}
                    :waits)
  => {:waits {:scroll #{:my-bthread}}}"
  [state bthread-name bid request-type]
  (let [event-fn (case request-type
                   :requests bid/request
                   :waits bid/wait-on
                   :blocks bid/block)
        key-of (index-key-fn request-type)
        event-types (event-fn bid)]
    (if (seq event-types)
      (reduce (fn [state requested-event]
                (update-in state [request-type (key-of requested-event)]
                           (fnil conj #{})
                           bthread-name))
              state
              event-types)
      state)))

(defn- spawn-only-bid?
  [bid]
  (and (seq (bid/bthreads bid))
       (empty? (bid/request bid))
       (empty? (bid/wait-on bid))
       (empty? (bid/block bid))))

(defn- retired-bid?
  "Return true when a bid means the bthread should leave the program.

  Both `nil` bids and spawn-only bids terminate the current bthread. Spawn-only
  bids may still contribute child bthreads to the transition result."
  [bid]
  (or (nil? bid)
      (spawn-only-bid? bid)))

(defn- notify-and-collect!
  "Notify `bthread-names` of `event` and collect their bids into a delta.

  When `skip-unchanged?` is true, a bthread whose new bid equals its current
  bid in `state` and spawns nothing is listed under `:unchanged-bthreads`. Its bid
  is still collected, but its events are not indexed, because its existing
  index entries remain valid."
  [state event bthread-names skip-unchanged?]
  (let [current-bids (get state :bthread->bid)]
    (reduce (fn [acc bthread-name]
              (let [bthread (get-in state [:name->bthread bthread-name])
                    _ (when-not bthread (println "No bthread found for" bthread-name))
                    bid (b/notify! bthread event)
                    bthreads (bid/bthreads bid)
                    retired? (retired-bid? bid)
                    unchanged? (and skip-unchanged?
                                    (not retired?)
                                    (empty? bthreads)
                                    (= bid (get current-bids bthread-name)))]
                (cond-> acc
                  (seq bthreads)
                  (-> (update :bthreads merge bthreads)
                      (assoc-in [:parent->child-bthreads bthread-name]
                                (into #{} (keys bthreads))))

                  retired?
                  (update :retired-bthreads (fnil conj #{}) bthread-name)

                  unchanged?
                  (-> (assoc-in [:bthread->bid bthread-name] bid)
                      (update :unchanged-bthreads (fnil conj #{}) bthread-name))

                  (not (or retired? unchanged?))
                  (-> (assoc-in [:bthread->bid bthread-name] bid)
                      (index-bid-events bthread-name bid :requests)
                      (index-bid-events bthread-name bid :waits)
                      (index-bid-events bthread-name bid :blocks)))))
            {:bthread->bid {}}
            bthread-names)))

(defn notify-step!
  "Like `(notify-bthreads! state event)`, but bthreads whose bid is unchanged
  are reported under `:unchanged-bthreads` instead of being indexed.

  Used by `bprogram.state/step`, which keeps their existing index entries."
  [state event]
  (notify-and-collect! state event (bthreads-to-notify state event) true))

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
    - :requests - index of requested events to requesting bthreads
  - event: The event that occurred (optional - if not provided, notifies ALL bthreads with nil event)

  Returns:
  A map containing only the updates from notified bthreads:
  - :bthread->bid - map of bthread names to their new bids
  - :requests - index of requested events to bthread names
  - :waits - index of waited event types to bthread names
  - :blocks - index of blocked event types to bthread names
  - :bthreads - newly spawned child bthreads
  - :parent->child-bthreads - spawn ordering links for priority bookkeeping
  - :retired-bthreads - bthread names that terminated during notification

  The caller is responsible for merging these updates into the main state."
  ([state]
   (notify-bthreads! state nil (keys (:name->bthread state))))
  ([state event]
   (notify-bthreads! state event (bthreads-to-notify state event)))
  ([state event bthread-names]
   (notify-and-collect! state event bthread-names false)))
