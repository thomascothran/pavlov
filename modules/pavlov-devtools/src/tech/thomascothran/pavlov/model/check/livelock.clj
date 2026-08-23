(ns tech.thomascothran.pavlov.model.check.livelock
  "Structural livelock detection for labeled transition systems.

  A livelock is a cycle contained entirely in the region of the graph that
  cannot reach a terminal event. Cycle detection uses a global white/gray/black
  coloring, so convergent subgraphs are explored once rather than once per
  path.

  This works by:

  a) starting with the terminal nodes
  b) removing all nodes from which a terminal node is reachable
     (leaving the trapped nodes)
  c) checking to see if there is a cycle in the remaining nodes"
  (:require [clojure.set :as set]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.graph.algo :as algo]))

(defn- terminal-node-ids
  [lts]
  (into #{}
        (comp (filter (comp e/terminal? :event))
              (map :to))
        (:edges lts)))

(defn- nodes-reaching-terminal
  "Return the node IDs from which a terminal event target is reachable.

  Returns nil if reverse reachability cannot be computed, preserving the
  model checker's existing fail-closed behavior for livelock analysis."
  [lts]
  (try
    (let [terminal-nodes (terminal-node-ids lts)
          reverse-adjacency
          (reduce (fn [index {:keys [from to]}]
                    (update index to (fnil conj #{}) from))
                  {}
                  (:edges lts))]
      (loop [reachable terminal-nodes
             frontier terminal-nodes]
        (if (empty? frontier)
          reachable
          (let [new-nodes (into #{}
                                (comp (mapcat reverse-adjacency)
                                      (remove reachable))
                                frontier)]
            (recur (into reachable new-nodes)
                   new-nodes)))))
    (catch Throwable _
      nil)))

(defn- outgoing-index-within
  [lts node-id?]
  (reduce (fn [index {:keys [from to] :as edge}]
            (if (and (node-id? from) (node-id? to))
              (update index from (fnil conj []) edge)
              index))
          {}
          (:edges lts)))

(defn- ordered-start-node-ids
  [lts node-id?]
  (into (cond-> []
          (node-id? (:root lts)) (conj (:root lts)))
        (comp (filter node-id?)
              (remove #{(:root lts)}))
        (keys (:nodes lts))))

(defn- find-cycle-edges
  "Return the first cycle in NODE-IDS as an exact edge witness, or nil.

  Gray nodes are on the active DFS path and black nodes have been completely
  explored. Black is global across all DFS roots, which bounds the search by
  the size of the induced graph rather than by its number of paths."
  [lts node-ids]
  (let [node-id? (set node-ids)
        outgoing-index (outgoing-index-within lts node-id?)
        start-node-ids (ordered-start-node-ids lts node-id?)]
    (loop [remaining-starts (seq start-node-ids)
           colors {}
           stack []
           edge-stack []
           node-id->edge-index {}]
      (if-let [{:keys [node-id remaining-edges]} (peek stack)]
        (if-let [edge (first remaining-edges)]
          (let [successor-id (:to edge)
                stack' (conj (pop stack)
                             {:node-id node-id
                              :remaining-edges (next remaining-edges)})]
            (case (get colors successor-id)
              :gray
              (let [cycle-edge-stack (conj edge-stack edge)]
                (subvec cycle-edge-stack
                        (get node-id->edge-index successor-id)))

              :black
              (recur remaining-starts
                     colors
                     stack'
                     edge-stack
                     node-id->edge-index)

              (let [edge-stack' (conj edge-stack edge)]
                (recur remaining-starts
                       (assoc colors successor-id :gray)
                       (conj stack'
                             {:node-id successor-id
                              :remaining-edges (seq (get outgoing-index successor-id))})
                       edge-stack'
                       (assoc node-id->edge-index
                              successor-id
                              (count edge-stack'))))))
          (recur remaining-starts
                 (assoc colors node-id :black)
                 (pop stack)
                 (if (> (count stack) 1)
                   (pop edge-stack)
                   edge-stack)
                 (dissoc node-id->edge-index node-id)))
        (when-let [start-node-id (first remaining-starts)]
          (let [remaining-starts (next remaining-starts)]
            (if (contains? colors start-node-id)
              (recur remaining-starts
                     colors
                     stack
                     edge-stack
                     node-id->edge-index)
              (recur remaining-starts
                     (assoc colors start-node-id :gray)
                     [{:node-id start-node-id
                       :remaining-edges (seq (get outgoing-index start-node-id))}]
                     []
                     {start-node-id 0}))))))))

(defn- path-edges-to-node
  [lts outgoing-index target-node-id]
  (let [successors (fn [node-id]
                     (algo/succ outgoing-index
                                (constantly true)
                                node-id))]
    (algo/find-path (:root lts)
                    successors
                    #(= target-node-id %))))

(defn find-livelocks
  "Return at most one structural livelock witness, or nil.

  The return shape matches the existing checker contract:

    [{:path  [event-types leading to the cycle]
      :cycle [event-types forming the cycle]}]

  Livelock checking is enabled unless CONFIG contains
  `:check-livelock? false`."
  [lts config]
  (when (not= false (:check-livelock? config))
    (when (and (seq (:nodes lts))
               (seq (:edges lts)))
      (when-let [terminal-reachable-node-ids (nodes-reaching-terminal lts)]
        (let [all-node-ids (set (keys (:nodes lts)))
              trapped-node-ids (set/difference all-node-ids
                                               terminal-reachable-node-ids)]
          (when (seq trapped-node-ids)
            (when-let [cycle-edges (find-cycle-edges lts trapped-node-ids)]
              (let [cycle-entry-node-id (:from (first cycle-edges))
                    outgoing-index (algo/lts->outgoing-index lts)
                    path-edges (path-edges-to-node lts
                                                   outgoing-index
                                                   cycle-entry-node-id)]
                [{:path (mapv (comp e/type :event) path-edges)
                  :cycle (mapv (comp e/type :event) cycle-edges)}]))))))))
