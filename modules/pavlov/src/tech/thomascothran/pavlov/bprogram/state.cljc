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

(defn- notification-spawned-bthreads
  "Read the child bthreads emitted by a notification pass.

  This keeps the notification result shape named at the call site so state
  transitions read in terms of lifecycle events rather than raw map keys."
  [notification-results]
  (get notification-results :bthreads))

(defn- notification-parent->child-bthreads
  "Read the parent-to-child spawn links from a notification pass.

  State uses these links to preserve spawn ordering semantics separately from
  the spawned bthread instances themselves."
  [notification-results]
  (get notification-results :parent->child-bthreads))

(defn- notification-index-delta
  "Read only the bid/event-index updates from a notification pass.

  Spawn bookkeeping is handled on a separate path, so this helper marks the
  portion of notification output that can be merged directly into state."
  [notification-results]
  (dissoc notification-results :bthreads :parent->child-bthreads))

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

(defn- update-bthread-registry
  "Merge newly available bthreads into the registry.

  State currently treats registration as additive, whether the bthreads came
  from the initial program definition or from a spawn during execution."
  [state bthreads]
  (update state :name->bthread merge (into {} bthreads)))

(defn- update-bthread-priorities
  "Apply the current priority bookkeeping for spawned bthreads.

  Priority updates remain separate from registry updates because spawn order is
  a distinct concern from name lookup, and later lifecycle changes will likely
  need to adjust them independently."
  [state spawned-bthreads parent->child-bthreads]
  (if spawned-bthreads
    (update state
            :bthreads-by-priority
            splice-bthread-priorities
            parent->child-bthreads)
    state))

(defn- initialize-spawned-bthreads
  [state spawned-bthreads parent->child-bthreads]
  (loop [state state
         spawned-bthreads spawned-bthreads
         parent->child-bthreads parent->child-bthreads]
    (if (seq spawned-bthreads)
      (let [notification-results (notification/notify-bthreads!
                                  {:name->bthread spawned-bthreads})
            next-spawned-bthreads
            (notification-spawned-bthreads notification-results)
            next-parent->child-bthreads
            (notification-parent->child-bthreads notification-results)
            state (-> state
                      (update-bthread-registry spawned-bthreads)
                      (update-bthread-priorities spawned-bthreads
                                                 parent->child-bthreads)
                      (merge-notification-results
                       (notification-index-delta notification-results)))]
        (recur state next-spawned-bthreads next-parent->child-bthreads))
      state)))

(defn- update-bthread-bids
  "Rebuild the active bid map for the next state.

  This combines bids from notified bthreads with any bids produced when newly
  spawned bthreads are initialized, while preserving the current semantics for
  triggered bthread removal."
  [state triggered-bthreads notification-bids spawned-bthreads spawned-init-results]
  (-> (get state :bthread->bid)
      (#(apply dissoc % triggered-bthreads))
      (into notification-bids)
      (cond-> spawned-bthreads
        (merge (get spawned-init-results :bthread->bid)))))

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
  (let [spawned-bthreads (notification-spawned-bthreads notification-results)]
    (if (seq spawned-bthreads)
      (initialize-spawned-bthreads state
                                   spawned-bthreads
                                   (notification-parent->child-bthreads
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
        with-next-event)))

(defn- remove-bthreads-from-event-index
  [event->threads triggered-bthreads]
  (into {}
        (map (fn [[event bthreads]]
               [event (->> bthreads
                           (remove (or triggered-bthreads #{}))
                           (into #{}))]))
        event->threads))

(defn- initialize-new-bthreads!
  [name->bthread]
  (notification/notify-bthreads! {:name->bthread name->bthread}))

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

(defn- initialize-step-spawned-bthreads!
  "Initialize child bthreads spawned during a step.

  Step-time spawns are initialized after the triggering event is processed so
  they contribute bids only to subsequent selection state."
  [spawned-bthreads]
  (when spawned-bthreads
    (initialize-new-bthreads! spawned-bthreads)))

(defn- update-event-index
  [event->threads event triggered-bthreads notification-index spawned-index]
  (-> event->threads
      (dissoc event)
      (remove-bthreads-from-event-index triggered-bthreads)
      (merge-event->bthreads notification-index)
      (cond-> (seq spawned-index)
        (merge-event->bthreads spawned-index))))

(defn- next-event-indexes
  "Build the step-time event indexes for the next state.

  The waits/requests/blocks indexes follow the same replacement pattern, so we
  compute them together to keep step assembly focused on lifecycle phases rather
  than three parallel map updates."
  [state event triggered-bthreads
   notification-waits notification-requests notification-blocks
   spawned-waits spawned-requests spawned-blocks]
  {:waits (update-event-index (get state :waits)
                              event
                              triggered-bthreads
                              notification-waits
                              spawned-waits)
   :requests (update-event-index (get state :requests)
                                 event
                                 triggered-bthreads
                                 notification-requests
                                 spawned-requests)
   :blocks (update-event-index (get state :blocks)
                               event
                               triggered-bthreads
                               notification-blocks
                               spawned-blocks)})

(defn- assemble-next-state
  "Assemble the next program state from a step notification pass.

  This is the single point where a processed event, the notification delta, and
  any newly initialized child bthreads are combined into the next scheduler
  snapshot."
  [{notification-bids :bthread->bid
    notification-waits :waits
    notification-requests :requests
    notification-blocks :blocks
    spawned-bthreads :bthreads
    :keys [parent->child-bthreads]}
   state
   event]
  (let [triggered-bthreads (triggered-bthreads state event)
        spawned-init-results
        (initialize-step-spawned-bthreads! spawned-bthreads)
        spawned-waits (get spawned-init-results :waits)
        spawned-requests (get spawned-init-results :requests)
        spawned-blocks (get spawned-init-results :blocks)
        {:keys [waits requests blocks]}
        (next-event-indexes state
                            event
                            triggered-bthreads
                            notification-waits
                            notification-requests
                            notification-blocks
                            spawned-waits
                            spawned-requests
                            spawned-blocks)

        next-bthread->bid
        (update-bthread-bids state
                             triggered-bthreads
                             notification-bids
                             spawned-bthreads
                             spawned-init-results)

        next-bthread-priorities
        (get (update-bthread-priorities state
                                        spawned-bthreads
                                        parent->child-bthreads)
             :bthreads-by-priority)

        next-state
        (assoc state
               :last-event event
               :waits waits
               :requests requests
               :blocks blocks
               :bthreads-by-priority next-bthread-priorities
               :bthread->bid next-bthread->bid
               :name->bthread (:name->bthread
                               (update-bthread-registry state
                                                        spawned-bthreads)))]
    (with-next-event next-state)))

(defn step
  "Return the next state based on the event"
  [state event]
  (-> (notification/notify-bthreads! state event)
      (assemble-next-state state event)))
