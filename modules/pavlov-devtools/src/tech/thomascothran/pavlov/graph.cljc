(ns tech.thomascothran.pavlov.graph
  "Graph construction for behavioral program visualization.

  Provides two graph representations:

  - `->lts` - Labeled Transition System using state identifiers as node IDs.
    States reached via different paths are merged into single nodes, showing
    convergence. Better for understanding the actual state space.

  Both functions take a map of named bthreads and return a graph with
  `:nodes` and `:edges` suitable for visualization tooling."
  (:require [tech.thomascothran.pavlov.search :as search])
  #?(:clj (:import [clojure.lang PersistentQueue])))

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

  Options:
    :max-nodes - Maximum nodes to explore before stopping (default: 1,000)

  Example:
    (->lts {:a bthread-a :b bthread-b})
    ;; => {:root root-id :nodes {...} :edges [...] :truncated false}

    (->lts bthreads {:max-nodes 100})
    ;; => {:root root-id :nodes {...} :edges [...] :truncated true}"
  ([bthreads] (->lts bthreads {:max-nodes 100}))
  ([bthreads {:keys [max-nodes] :as _opts}]
   (let [nav (search/make-navigator bthreads)
         root-wrapped (search/root nav)
         root-id (search/identifier nav root-wrapped)
         empty-queue #?(:clj PersistentQueue/EMPTY
                        :cljs cljs.core/PersistentQueue.EMPTY)]
     ;; Custom BFS loop that calls succ exactly once per state.
     ;; This avoids the bug where calling succ twice returns different
     ;; states due to mutable bthread state.
     (loop [queue (conj empty-queue root-wrapped)
            seen #{}
            nodes {}
            edges []
            truncated false]
       (if (or (empty? queue) truncated)
         {:root root-id
          :nodes nodes
          :edges edges
          :truncated truncated}
         (let [wrapped (peek queue)
               queue (pop queue)
               identifier (search/identifier nav wrapped)]
           (if (contains? seen identifier)
             ;; Already processed, skip
             (recur queue seen nodes edges truncated)
             ;; Process this node
             (let [;; Check if we've hit the limit
                   hit-limit? (and max-nodes (>= (count nodes) max-nodes))
                   ;; Add current node
                   nodes (assoc nodes identifier (->node-value wrapped))
                   ;; Mark as seen
                   seen (conj seen identifier)]
               (if hit-limit?
                 ;; Stop processing, mark as truncated
                 (recur queue seen nodes edges true)
                 ;; Get successors ONCE and use for both edges and queue
                 (let [successors (search/succ nav wrapped)
                       ;; Create edges from successors
                       new-edges (mapv (fn [{:keys [state event]}]
                                         {:from identifier
                                          :to (search/identifier nav state)
                                          :event event})
                                       successors)
                       ;; Also add successor nodes immediately
                       nodes (reduce (fn [nodes {:keys [state]}]
                                       (let [succ-id (search/identifier nav state)]
                                         (assoc nodes succ-id (->node-value state))))
                                     nodes
                                     successors)
                       ;; Add successor states to queue
                       queue (reduce (fn [q {:keys [state]}]
                                       (conj q state))
                                     queue
                                     successors)]
                   (recur queue seen nodes (into edges new-edges) truncated)))))))))))
