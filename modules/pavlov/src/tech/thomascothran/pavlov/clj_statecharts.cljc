(ns ^:alpha tech.thomascothran.pavlov.clj-statecharts
  "Experimental, alpha functionality for optional clj-statecharts integration.
  Add clj-statecharts/clj-statecharts
  to your application's dependencies before requiring this namespace.

  Charts use the clj-statecharts syntax, with ::bid on any state node.
  A bid is a literal Pavlov bid or a pure (fn [chart-state] bid). Active
  ancestors and parallel regions contribute bids; :on events are observed
  automatically. Guards, actions, and bid functions must be pure. Use
  fsm/assign for context updates and requested events for external effects.

  Mark states ::hot true for liveness obligations, or ::invariant-violated true
  for forbidden states. Invariant violations include transient state entry.

  Built-in timers are unsupported: supply timer events through Pavlov.
  clj-statecharts uses :regions for parallel children, not :states."
  (:require [statecharts.core :as fsm]
            [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as event]))

(defn- prepare-chart [node path]
  (when (or (contains? node :after) (contains? node :scheduler))
    (throw (ex-info "Use Pavlov events for timers, not :after or :scheduler"
                    {:node node})))
  (doseq [k [::hot ::invariant-violated]]
    (when (and (contains? node k) (not (boolean? (get node k))))
      (throw (ex-info "State property markers must be booleans"
                      {:path path :property k :value (get node k)}))))
  (reduce (fn [result k]
            (if (contains? node k)
              (assoc result k
                     (into {} (map (fn [[id child]]
                                     [id (prepare-chart child (conj path id))]))
                           (get node k)))
              result))
          (cond-> (dissoc node ::bid ::hot ::invariant-violated)
            (::invariant-violated node)
            (update :entry
                    (fn [actions]
                      (into [(fsm/assign
                              (fn [state _]
                                (update state ::violated-states (fnil conj #{}) path)))]
                            (cond (nil? actions) []
                                  (vector? actions) actions
                                  :else [actions])))))
          [:states :regions]))

;; Decode the documented runtime _state representation without depending on
;; statecharts.impl: a keyword, a path vector, or a map of parallel regions.
(defn- active-nodes [node state-value]
  (cons node
        (cond
          (= :parallel (:type node))
          (mapcat (fn [[id value]]
                    (active-nodes (get-in node [:regions id]) value))
                  (sort-by key state-value))

          (contains? node :states)
          (let [[head & tail] (if (vector? state-value)
                               state-value
                               [state-value])
                [id child-value] (if (map? head)
                                   (first head)
                                   [head (when (seq tail) (vec tail))])]
            (active-nodes (get-in node [:states id]) child-value))

          :else nil)))

(defn- combine-requests [bids]
  (let [requests (filter seq (map bid/request bids))]
    (cond
      (empty? requests) #{}
      (= 1 (count requests)) (first requests)
      (every? set? requests) (into #{} cat requests)
      :else
      (throw (ex-info "Multiple active request bids must use sets; ordered requests cannot be merged without changing priority"
                      {:requests requests})))))

(defn- combine-children [bids]
  (reduce (fn [children [id child]]
            (when (contains? children id)
              (throw (ex-info "Active state bids contain duplicate child bthread names"
                              {:child-name id})))
            (assoc children id child))
          {} (mapcat bid/bthreads bids)))

(defn state->bid
  "Derive a Pavlov bid from an original chart definition and runtime state.

  Includes bids from the root, active ancestors, and active parallel regions.
  Unions waits and blocks, observes active :on event types, and marks the bid
  hot when any contribution or active state's ::hot marker is true.
  A missing/nil state bid contributes nothing.
  Multiple nonempty request contributions must be sets; a single contribution
  retains its ordering. Duplicate child bthread names are errors.

  Entering a state marked ::invariant-violated records its path under
  ::violated-states in the runtime state, including transient states. A recorded
  violation (or active forbidden state) replaces the chart's ordinary bid with
  a terminal ::invariant-violated event recognized by Pavlov's safety checker.

  This is also useful for inspecting the bid after restoring a chart snapshot."
  [chart state]
  (let [nodes (active-nodes chart (:_state state))]
    (if (or (seq (::violated-states state))
            (some ::invariant-violated nodes))
      {:request #{{:type ::invariant-violated
                   :invariant-violated true
                   :terminal true
                   :state state}}}
      (let [bids (mapv (fn [node]
                         (let [value (::bid node)]
                           (if (fn? value) (value state) value)))
                       nodes)
            waits (into #{} (mapcat (comp keys :on)) nodes)
            children (combine-children bids)]
        (cond-> {:request (combine-requests bids)
                 :wait-on (into waits (mapcat bid/wait-on) bids)
                 :block (into #{} (mapcat bid/block) bids)}
          (or (some ::hot nodes) (some bid/hot bids)) (assoc :hot true)
          (seq children) (assoc :bthreads children))))))

(defn bthread
  "Create a Pavlov bthread from an uncompiled clj-statecharts definition.

  Put ::bid on state nodes, either a literal bid or (fn [chart-state] bid).
  Functions receive the complete state, including :_state and context fields.
  The adapter observes :on events, runs the chart to its stable configuration,
  and derives the next bid. Requests persist until state/context changes remove
  them. Transition guards do not block global event selection.

  The machine requires :id, as in clj-statecharts. Options are passed to b/step
  (e.g. :label). b/state and b/set-state expose/restore the chart's immutable
  runtime state; nil is the uninitialized state. A nil notification derives a
  bid without re-entering an already initialized/restored chart.

  Pure fsm/assign actions execute normally, including between eventless
  transitions. No services, timers, or effectful actions should run here."
  ([chart] (bthread chart nil))
  ([chart opts]
   (let [machine (fsm/machine (prepare-chart chart []))]
     (b/step
      (fn [state selected-event]
        (let [next-state
              (cond
                (nil? state) (fsm/initialize machine)
                (nil? selected-event) state
                :else (fsm/transition
                       machine state
                       (if (map? selected-event)
                         (assoc selected-event :type (event/type selected-event))
                         {:type (event/type selected-event)
                          ::event selected-event})
                       {:ignore-unknown-event? true}))]
          [next-state (state->bid chart next-state)]))
      opts))))
