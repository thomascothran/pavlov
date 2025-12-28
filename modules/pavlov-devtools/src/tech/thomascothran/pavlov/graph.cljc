(ns tech.thomascothran.pavlov.graph
  "Graph construction for behavioral program visualization.

  Provides two graph representations:

  - `->graph` - Uses event paths as node IDs. Useful when you want to see
    the full execution tree with all paths explicitly represented.

  - `->lts` - Labeled Transition System using state identifiers as node IDs.
    States reached via different paths are merged into single nodes, showing
    convergence. Better for understanding the actual state space.

  Both functions take a map of named bthreads and return a graph with
  `:nodes` and `:edges` suitable for visualization tooling."
  (:require [tech.thomascothran.pavlov.search :as search]))

(defn ->graph
  "Return a graph representation for the supplied bthreads.

  The returned structure will be map-based, with nodes and edges keyed in
  a way suitable for visualization and analysis tooling.

  Example:
  ```clojure
  (->graph {:go-to-work go-to-work-bthread
            :start-work start-work-bthread})
  ;;=> {:nodes {...}
  ;;    :edges {...}}
  ```
  "
  [bthreads]
  (let [nav (search/make-navigator bthreads)]
    (search/bfs-reduce
     nav
     (fn [{:keys [nodes edges id->path]} wrapped]
       (let [identifier (search/identifier nav wrapped)
             node-id (:path wrapped)
             last-event (get-in wrapped [:bprogram/state :last-event])
             id->path (if (contains? id->path identifier)
                        id->path
                        (assoc id->path identifier node-id))
             successors (search/succ nav wrapped)
             nodes (assoc nodes node-id {:path node-id
                                         :identifier identifier
                                         :event last-event
                                         :wrapped wrapped})
             [edges id->path]
             (reduce (fn [[edges id->path] {:keys [state event]}]
                       (let [succ-identifier (search/identifier nav state)
                             succ-path (get id->path succ-identifier (:path state))
                             id->path (if (contains? id->path succ-identifier)
                                        id->path
                                        (assoc id->path succ-identifier succ-path))]
                         [(conj edges {:from node-id
                                       :to succ-path
                                       :event event})
                          id->path]))
                     [edges id->path]
                     successors)]
         {:nodes nodes :edges edges :id->path id->path}))
     {:nodes {} :edges [] :id->path {}})))

(defn- ->node-value
  "Extract minimal node data from wrapped state.

  Includes only state-identity-relevant data, excluding path-dependent
  fields like :path, :last-event, :next-event, and non-serializable
  fields like :name->bthread."
  [wrapped]
  (let [bp-state (:bprogram/state wrapped)]
    {:bthread->bid (:bthread->bid bp-state)
     :bthreads-by-priority (:bthreads-by-priority bp-state)
     :saved-bthread-states (:saved-bthread-states wrapped)
     :requests (:requests bp-state)
     :waits (:waits bp-state)
     :blocks (:blocks bp-state)}))

(defn ->lts
  "Return a Labeled Transition System (LTS) for the supplied bthreads.

  Unlike `->graph`, uses state identifiers as node IDs rather than event paths.
  This means states reached via different paths are merged into single nodes,
  making convergence visible in the graph.

  Returns a map with:
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

  Options:
    :max-nodes - Maximum nodes to explore before stopping (default: 1,000)

  Example:
    (->lts {:a bthread-a :b bthread-b})
    ;; => {:nodes {...} :edges [...] :truncated false}

    (->lts bthreads {:max-nodes 100})
    ;; => {:nodes {...} :edges [...] :truncated true}"
  ([bthreads] (->lts bthreads {:max-nodes 100}))
  ([bthreads {:keys [max-nodes] :as _opts}]
   (let [nav (search/make-navigator bthreads)]
     (search/bfs-reduce
      nav
      (fn [{:keys [edges nodes truncated]} wrapped]
        ;; Check if we've reached the max-nodes limit before processing
        (if (and max-nodes (>= (count nodes) max-nodes))
          (reduced {:edges edges :nodes nodes :truncated true})
          (let [identifier (search/identifier nav wrapped)
                ;; Add the current node to the nodes map
                nodes (assoc nodes identifier (->node-value wrapped))
                ;; Check again after adding current node
                stop-after-current? (and max-nodes (>= (count nodes) max-nodes))
                successors (if stop-after-current?
                             [] ;; Don't process successors if we've hit the limit
                             (search/succ nav wrapped))
                [edges nodes] (reduce (fn [[edges nodes] {:keys [state event]}]
                                        (let [succ-identifier (search/identifier nav state)]
                                          [(conj edges {:from identifier
                                                        :to succ-identifier
                                                        :event event})
                                           ;; Also add successor nodes
                                           (assoc nodes succ-identifier (->node-value state))]))
                                      [edges nodes]
                                      successors)]
            {:edges edges :nodes nodes :truncated (or truncated false)})))
      {:edges [] :nodes {} :truncated false}))))
