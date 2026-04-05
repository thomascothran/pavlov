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

(defn- notification-spawned-name->bthread
  "Read the child bthreads emitted by a notification pass.

  This keeps the notification result shape named at the call site so state
  transitions read in terms of lifecycle events rather than raw map keys."
  [notification-results]
  (get notification-results :bthreads))

(defn- notification-parent->child-bthread-names
  "Read the parent-to-child spawn links from a notification pass.

  State uses these links to preserve spawn ordering semantics separately from
  the spawned bthread instances themselves."
  [notification-results]
  (get notification-results :parent->child-bthreads))

(defn- notification-retired-bthread-names
  "Read the bthreads that should be deregistered after a notification pass.

  Notification determines lifecycle outcome for notified bthreads, while state
  owns removing them from registries, indexes, and priority bookkeeping."
  [notification-results]
  (get notification-results :retired-bthreads))

(defn- notification-index-delta
  "Read only the bid/event-index updates from a notification pass.

  Spawn bookkeeping is handled on a separate path, so this helper marks the
  portion of notification output that can be merged directly into state."
  [notification-results]
  (dissoc notification-results
          :bthreads
          :parent->child-bthreads
          :retired-bthreads))

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
          (if (sequential? bthread-priorities) [] #{})
          bthread-priorities))

;; Here, we can put the bthreads in order of priority
(defn- merge-notification-results
  [state {:keys [bthread->bid waits requests blocks]}]
  (-> state
      (update :bthread->bid merge bthread->bid)
      (update :waits merge-event->bthreads waits)
      (update :requests merge-event->bthreads requests)
      (update :blocks merge-event->bthreads blocks)))

(defn- remove-bthread-names-from-event-index
  "Remove bthread names from an event index while preserving its shape.

  Event indexes track reachability into future steps, so lifecycle cleanup has
  to prune names here as well as in the registry and active bid map."
  [event->threads removed-bthread-names]
  (into {}
        (map (fn [[event bthreads]]
               [event (->> bthreads
                           (remove (or removed-bthread-names #{}))
                           (into #{}))]))
        event->threads))

(defn- update-bthread-registry
  "Merge newly available bthreads into the registry.

  State currently treats registration as additive, whether the bthreads came
  from the initial program definition or from a spawn during execution."
  [state bthreads]
  (update state :name->bthread merge (into {} bthreads)))

(defn- remove-bthread-names-from-priorities
  "Drop bthread names from the priority structure without changing its kind.

  Ordered bprograms must stay ordered vectors, while equal-priority bprograms
  must stay sets."
  [bthreads-by-priority removed-bthread-names]
  (let [removed-bthread-names (or removed-bthread-names #{})]
    (into (if (sequential? bthreads-by-priority) [] #{})
          (remove removed-bthread-names)
          bthreads-by-priority)))

(defn- update-bthread-priorities
  "Apply the current priority bookkeeping for spawned bthreads.

  Priority updates remain separate from registry updates because spawn order is
  a distinct concern from name lookup, and later lifecycle changes will likely
  need to adjust them independently."
  [state spawned-bthreads parent->child-bthreads]
  (if (seq spawned-bthreads)
    (let [spawned-bthread-names (into #{} (map first) spawned-bthreads)]
      (update state
              :bthreads-by-priority
              (fn [bthreads-by-priority]
                (-> bthreads-by-priority
                    (remove-bthread-names-from-priorities spawned-bthread-names)
                    (splice-bthread-priorities parent->child-bthreads)))))
    state))

(defn- deregister-bthread-names
  "Remove terminated bthreads from all lifecycle-owned state indexes.

  A bthread is not fully gone until it has been removed from lookup by name,
  scheduling priority bookkeeping, active bids, and the event indexes that can
  otherwise keep it reachable on later steps."
  [state retired-bthread-names]
  (let [retired-bthread-names (or retired-bthread-names #{})]
    (if (seq retired-bthread-names)
      (-> state
          (update :name->bthread #(apply dissoc % retired-bthread-names))
          (update :bthread->bid #(apply dissoc % retired-bthread-names))
          (update :bthreads-by-priority remove-bthread-names-from-priorities retired-bthread-names)
          (update :waits remove-bthread-names-from-event-index retired-bthread-names)
          (update :requests remove-bthread-names-from-event-index retired-bthread-names)
          (update :blocks remove-bthread-names-from-event-index retired-bthread-names))
      state)))

(defn- initialize-spawned-bthreads
  "Recursively initialize spawned bthreads and fold them into scheduler state.

  Spawned bthreads can themselves spawn descendants before the next event is
  selected. This helper walks that tree, registers the live bthreads, and then
  removes any spawned bthreads that terminated during initialization."
  [state spawned-bthreads parent->child-bthreads]
  (if (seq spawned-bthreads)
    (let [notification-results (notification/notify-bthreads!
                                {:name->bthread spawned-bthreads})
          next-spawned-bthreads
          (notification-spawned-name->bthread notification-results)
          next-parent->child-bthreads
          (notification-parent->child-bthread-names notification-results)
          retired-bthread-names
          (notification-retired-bthread-names notification-results)
          state (-> state
                    (update-bthread-registry spawned-bthreads)
                    (update-bthread-priorities spawned-bthreads
                                               parent->child-bthreads)
                    (merge-notification-results
                     (notification-index-delta notification-results)))]
      (-> (initialize-spawned-bthreads state
                                       next-spawned-bthreads
                                       next-parent->child-bthreads)
          (deregister-bthread-names retired-bthread-names)))
    state))

(defn- update-bthread-bids
  "Rebuild the active bid map for the next state.

  Step updates replace bids for triggered bthreads with the bids reported by the
  current notification pass. Spawned bthreads are initialized separately and
  merged through the spawned-bthread initialization pipeline."
  [state triggered-bthreads notification-bids]
  (-> (get state :bthread->bid)
      (#(apply dissoc % triggered-bthreads))
      (into notification-bids)))

(defn- initial-bthread-priorities
  [named-bthreads]
  (if (map? named-bthreads)
    (into #{} (map first) named-bthreads)
    (into [] (map first) named-bthreads)))

(defn- initial-state
  [named-bthreads]
  {:bthread->bid {}
   :last-event nil
   :name->bthread (into {} named-bthreads)
   :bthreads-by-priority (initial-bthread-priorities named-bthreads)})

(defn- merge-initial-notification-results
  [state notification-results]
  (merge state
         (notification-index-delta notification-results)))

(defn- initialize-startup-bthreads
  [state notification-results]
  (let [spawned-bthreads (notification-spawned-name->bthread notification-results)]
    (if (seq spawned-bthreads)
      (initialize-spawned-bthreads state
                                   spawned-bthreads
                                   (notification-parent->child-bthread-names
                                    notification-results))
      state)))

(defn- with-next-event
  [state]
  (assoc state :next-event (next-event state)))

(defn init
  "Initiate the state"
  [named-bthreads]
  (let [state (initial-state named-bthreads)
        notification-results (notification/notify-bthreads! state)]
    (-> state
        (merge-initial-notification-results notification-results)
        (initialize-startup-bthreads notification-results)
        (deregister-bthread-names (notification-retired-bthread-names notification-results))
        with-next-event)))

(defn- triggered-bthreads
  "Identify the bthreads that advance in response to the current event.

  These names drive all step-time state replacement: event indexes are updated,
  current bids are replaced, and later lifecycle changes will use the same seam
  for deregistration semantics."
  [state event]
  (into #{}
        (mapcat #(get % (event/type event)))
        [(get state :waits)
         (get state :requests)]))

(defn- update-event-index
  [event->threads event triggered-bthreads notification-index spawned-index]
  (-> event->threads
      (dissoc event)
      (remove-bthread-names-from-event-index triggered-bthreads)
      (merge-event->bthreads notification-index)
      (cond-> (seq spawned-index)
        (merge-event->bthreads spawned-index))))

(defn- next-event-indexes
  "Build the step-time event indexes for the next state.

  The waits/requests/blocks indexes follow the same replacement pattern, so we
  compute them together to keep step assembly focused on lifecycle phases rather
  than three parallel map updates."
  [state event triggered-bthreads
   notification-waits notification-requests notification-blocks]
  {:waits (update-event-index (get state :waits)
                              event
                              triggered-bthreads
                              notification-waits
                              nil)
   :requests (update-event-index (get state :requests)
                                 event
                                 triggered-bthreads
                                 notification-requests
                                 nil)
   :blocks (update-event-index (get state :blocks)
                               event
                               triggered-bthreads
                               notification-blocks
                               nil)})

(defn- assemble-next-state
  "Assemble the next program state from a step notification pass.

  This is the single point where a processed event, the notification delta, and
  any newly initialized child bthreads are combined into the next scheduler
  snapshot."
  [{notification-bids :bthread->bid
    notification-waits :waits
    notification-requests :requests
    notification-blocks :blocks
    retired-bthreads :retired-bthreads
    spawned-bthreads :bthreads
    :keys [parent->child-bthreads]}
   state
   event]
  (let [triggered-bthreads (triggered-bthreads state event)
        {:keys [waits requests blocks]}
        (next-event-indexes state
                            event
                            triggered-bthreads
                            notification-waits
                            notification-requests
                            notification-blocks)

        next-bthread->bid
        (update-bthread-bids state
                             triggered-bthreads
                             notification-bids)

        next-state
        (assoc state
               :last-event event
               :waits waits
               :requests requests
               :blocks blocks
               :bthread->bid next-bthread->bid)]
    (-> next-state
        (initialize-spawned-bthreads spawned-bthreads parent->child-bthreads)
        (deregister-bthread-names retired-bthreads)
        with-next-event)))

(defn step
  "Return the next state based on the event"
  [state event]
  (-> (notification/notify-bthreads! state event)
      (assemble-next-state state event)))
