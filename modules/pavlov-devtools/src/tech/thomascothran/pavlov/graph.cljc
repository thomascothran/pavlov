(ns tech.thomascothran.pavlov.graph
  "Graph construction for behavioral program visualization.

  Provides two graph representations:

  - `->lts` - Labeled Transition System using state identifiers as node IDs.
    States reached via different paths are merged into single nodes, showing
    convergence. Better for understanding the actual state space.

  Both functions take a map of named bthreads and return a graph with
  `:nodes` and `:edges` suitable for visualization tooling.

  ## Nodes

  In the LTS graph, nodes are bprogram states. The program state includes both:

  - the state of all bthreads in the bprogram
  - the bids proffered by bthreads

  ## Edges

  In the LTS graph, the edges are the events that transition the bthread state.
  These are the events which can be selected, given the priority of the
  bthreads and the priority of requests in a given bid.

  ## Branches

  Branches exist in programs where multiple requested events of equal priority
  are for selection.

  Branches can occur either at the bthread level or the bid level.

  ### Bthread level branching

  To cause branching at the bthread level, bthreads must have equal priority. This
  is done by passing a map of bthread-name to bthread to `->lts`. To avoid branching
  at the bthread level, bthreads can be passed as a list of tuples:

  [[:bthread-name bthread] ...]

  ### Bid-level branching

  Branches at the bid level can be created using a set rather than a vector for the
  requests, like so:

  ```clojure
  (b/scenario [{:request #{{:type :event-a} {:type :event-b}}}])
  ```

  This creates a branch in the execution graph.

  ### Combination of branches

  Note that branching compounds.

  First, the eligible bids will be seleted - that is,
  bids the request unblocked events. If the bthreads are in a map, this will be
  all bids with unblocked events. If the bthreads are provided in a sequence of tuples
  then it will be at most one bid.

  Second, the eligible requested events from the eligible bid will be selected.
  These will then be aggregated together into the total branching events. "
  (:require [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.bprogram.notification :as notification]
            [tech.thomascothran.pavlov.bprogram.state :as state]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.event.selection :as selection])
  #?(:clj (:import [clojure.lang PersistentQueue])))

;; ---------------------------------------------------------------------------
;; State space exploration
;;
;; Bthreads are stateful objects shared by every explored state, so each
;; state carries a snapshot of the bthread states (`saved`) that is restored
;; before stepping from it.

(defn- save-bthread-states
  [name->bthread]
  (persistent!
   (reduce-kv (fn [saved bthread-name bthread]
                (assoc! saved bthread-name (b/state bthread)))
              (transient {})
              name->bthread)))

(defn- restore-bthread-states!
  ([name->bthread saved]
   (run! (fn [[bthread-name bthread]]
           (when (contains? saved bthread-name)
             (b/set-state bthread (get saved bthread-name))))
         name->bthread))
  ([name->bthread saved bthread-names]
   (run! (fn [bthread-name]
           (when (contains? saved bthread-name)
             (b/set-state (get name->bthread bthread-name)
                          (get saved bthread-name))))
         bthread-names)))

(defn- state-key-bids
  "Drop each bid's `:bthreads` from the state key.

  Spawns happen in the same notification pass that produced the bid, so a
  stored bid has already spawned. Live children are identified by their own
  saved states, and keeping `:bthreads` would split states that differ only in
  which children a stateless spawner last started. Returns `bthread->bid`
  itself when no bid spawns, so the key shares structure with the node payload."
  [bthread->bid]
  (if (some (fn [[_ bid]] (and (map? bid) (bid/bthreads bid))) bthread->bid)
    (update-vals bthread->bid
                 (fn [bid]
                   (if (and (map? bid) (bid/bthreads bid))
                     (dissoc bid :bthreads)
                     bid)))
    bthread->bid))

(defn- state-key
  "Identity of an explored state: whether it was entered by a terminal event,
  the bthread snapshots, and the current bids."
  [bp-state saved]
  [(get (:last-event bp-state) :terminal)
   saved
   (state-key-bids (:bthread->bid bp-state))])

(defn- ->node-value
  "Node payload. Excludes path-dependent and non-serializable fields such as
  `:last-event` and `:name->bthread`."
  [bp-state saved]
  (let [bthread->bid (:bthread->bid bp-state)]
    {:bthread->bid bthread->bid
     :bthreads-by-priority (:bthreads-by-priority bp-state)
     :saved-bthread-states saved
     :requests (:requests bp-state)
     :waits (:waits bp-state)
     :blocks (:blocks bp-state)
     :hot (boolean (some bid/hot (vals bthread->bid)))}))

(defn- successors
  "Return [[event next-bp-state next-saved] ...] for every selectable event.

  Stepping only notifies the bthreads woken by the event, so between events
  only those bthreads are restored, and the next snapshot only re-reads them.
  A step that spawns or retires bthreads falls back to a full restore and a
  full snapshot."
  [bp-state saved]
  (if (event/terminal? (:last-event bp-state))
    []
    (let [name->bthread (:name->bthread bp-state)
          events (selection/prioritized-events (:bthreads-by-priority bp-state)
                                               (:bthread->bid bp-state)
                                               ;; Keys are the blocked event types
                                               (:blocks bp-state))]
      (restore-bthread-states! name->bthread saved)
      (loop [events (seq events)
             dirty nil
             out (transient [])]
        (if-let [e (first events)]
          (let [_ (cond
                    (= :all dirty) (restore-bthread-states! name->bthread saved)
                    (some? dirty) (restore-bthread-states! name->bthread saved dirty))
                woken (notification/bthreads-to-notify bp-state e)
                next-bp-state (state/step bp-state e)
                [next-saved dirty]
                (if (identical? name->bthread (:name->bthread next-bp-state))
                  [(reduce (fn [m bthread-name]
                             (assoc m bthread-name
                                    (b/state (get name->bthread bthread-name))))
                           saved
                           woken)
                   woken]
                  [(save-bthread-states (:name->bthread next-bp-state)) :all])]
            (recur (next events)
                   dirty
                   (conj! out [e next-bp-state next-saved])))
          (persistent! out))))))

(defn ->lts
  "Return a Labeled Transition System (LTS) for the supplied bthreads.

  Unlike `->graph`, uses state identifiers as node IDs rather than event paths.
  This means states reached via different paths are merged into single nodes,
  making convergence visible in the graph.

  Returns a map with:
    :root      - Identifier of the initial state
    :nodes     - Map of state-identifier -> node data
    :edges     - Vector of {:from id, :to id, :event e}
    :truncated - true if exploration was cut short by limits

  Node data contains:
    :bthread->bid        - Map of bthread name to current bid
    :bthreads-by-priority - Ordered collection of bthread names
    :saved-bthread-states - Internal bthread state snapshots
    :requests            - Map of event -> requesting bthreads
    :waits               - Map of event -> waiting bthreads
    :blocks              - Map of event -> blocking bthreads
    :hot                 - True when any active bthread is hot

  Options:
    :max-nodes - Maximum nodes to explore before stopping (default: 1,000)

  Example:
    (->lts {:a bthread-a :b bthread-b})
    ;; => {:root root-id :nodes {...} :edges [...] :truncated false}

    (->lts bthreads {:max-nodes 100})
    ;; => {:root root-id :nodes {...} :edges [...] :truncated true}"
  ([bthreads] (->lts bthreads {:max-nodes 100}))
  ([bthreads {:keys [max-nodes] :as _opts}]
   ;; Breadth-first search that deduplicates states when they are discovered.
   ;; Node ids are dense and assigned in discovery order. A node's payload is
   ;; rewritten each time the node is reached, so the last path to reach it
   ;; wins (states that differ only in `:bthreads` share a node).
   (let [root-state (state/init bthreads)
         root-saved (save-bthread-states (:name->bthread root-state))
         !ids (volatile! (transient {}))
         id-of (fn [k] (get @!ids k))
         intern! (fn [k]
                   (let [id (count @!ids)]
                     (vswap! !ids assoc! k id)
                     id))
         root-id (intern! (state-key root-state root-saved))
         over-limit? (fn [nodes] (and max-nodes (>= (count nodes) max-nodes)))
         result (fn [nodes edges truncated?]
                  {:root root-id
                   :nodes (persistent! nodes)
                   :edges (persistent! edges)
                   :truncated truncated?})]
     (loop [queue (conj #?(:clj PersistentQueue/EMPTY
                           :cljs cljs.core/PersistentQueue.EMPTY)
                        [root-id root-state root-saved])
            nodes (transient {})
            edges (transient [])]
       (if-let [[id bp-state saved] (peek queue)]
         (if (over-limit? nodes)
           (result nodes edges true)
           (let [nodes (assoc! nodes id (->node-value bp-state saved))
                 queue (pop queue)
                 succs (successors bp-state saved)]
             (if (over-limit? nodes)
               ;; This node reached the limit, so it is not expanded. The
               ;; result is truncated if anything is left unexplored.
               (result nodes edges
                       (boolean
                        (or (seq queue)
                            (some (fn [[_ next-bp-state next-saved]]
                                    (nil? (id-of (state-key next-bp-state next-saved))))
                                  succs))))
               (let [[queue nodes edges]
                     (reduce
                      (fn [[queue nodes edges] [e next-bp-state next-saved]]
                        (let [k (state-key next-bp-state next-saved)
                              known-id (id-of k)
                              to (or known-id (intern! k))]
                          [(if known-id
                             queue
                             (conj queue [to next-bp-state next-saved]))
                           (assoc! nodes to (->node-value next-bp-state next-saved))
                           (conj! edges {:from id :to to :event e})]))
                      [queue nodes edges]
                      succs)]
                 (recur queue nodes edges)))))
         (result nodes edges false))))))
