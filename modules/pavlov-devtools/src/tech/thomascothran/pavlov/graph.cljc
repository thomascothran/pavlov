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
  (b/bids [{:request #{{:type :event-a} {:type :event-b}}}])
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
  (:require [tech.thomascothran.pavlov.search :as search]))

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
         root-id (search/identifier nav root-wrapped)]
     (assoc
      (search/bfs-reduce
       nav
       (fn [{:keys [edges nodes]} wrapped]
         (if (and max-nodes (>= (count nodes) max-nodes))
           (reduced {:edges edges :nodes nodes :truncated true})
           (let [identifier (search/identifier nav wrapped)
                 nodes (assoc nodes identifier (->node-value wrapped))
                 stop-after-current? (and max-nodes (>= (count nodes) max-nodes))
                 successors (if stop-after-current?
                              []
                              (search/succ nav wrapped))
                 new-edges (mapv (fn [{:keys [state event]}]
                                   {:from identifier
                                    :to (search/identifier nav state)
                                    :event event})
                                 successors)
                 ;; Also add successor nodes
                 nodes (reduce (fn [nodes {:keys [state]}]
                                 (assoc nodes
                                        (search/identifier nav state)
                                        (->node-value state)))
                               nodes
                               successors)]
             {:edges (into edges new-edges)
              :nodes nodes
              :truncated false})))
       {:edges [] :nodes {} :truncated false})
      :root root-id))))
