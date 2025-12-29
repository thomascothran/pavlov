(ns tech.thomascothran.pavlov.model.check
  "Model checking for behavioral programs.

  This namespace provides model checking capabilities for behavioral programs
  by implementing state-space exploration and graph analysis.

  The main entry point is the `check` function which builds an LTS graph
  and analyzes it for violations."
  (:require [tech.thomascothran.pavlov.graph :as graph]))

;; Internal implementation details below

(defn- assemble-all-bthreads
  "Assembles all bthreads with proper priority ordering."
  [config]
  (let [;; Create bthreads from each category
        safety-bthreads (get config :safety-bthreads)
        main-bthreads (get config :bthreads)
        env-bthreads (get config :environment-bthreads)]
    ;; Order matters: safety -> main -> env -> deadlock
    (reduce into []
            [safety-bthreads
             main-bthreads
             env-bthreads])))

(defn- find-path
  "Find a path from root to target node in the LTS graph.
  Returns a vector of events, or nil if no path exists."
  [lts target-id]
  (let [{:keys [root edges]} lts
        ;; Build adjacency map: node-id -> [{:to id :event e}]
        adjacency (reduce (fn [acc {:keys [from to event]}]
                            (update acc from (fnil conj []) {:to to :event event}))
                          {}
                          edges)]
    (loop [queue (conj clojure.lang.PersistentQueue/EMPTY {:id root :path []})
           visited #{}]
      (if-let [{:keys [id path]} (peek queue)]
        (if (= id target-id)
          path
          (if (visited id)
            (recur (pop queue) visited)
            (let [neighbors (get adjacency id [])
                  new-states (map (fn [{:keys [to event]}]
                                    {:id to
                                     :path (conj path (if (keyword? event)
                                                        event
                                                        (:type event)))})
                                  neighbors)]
              (recur (into (pop queue) new-states)
                     (conj visited id)))))
        nil))))

(defn- find-safety-violations
  "Scan LTS edges for safety violations.
  Returns violation map or nil."
  [lts]
  (let [{:keys [edges nodes]} lts]
    (some (fn [{:keys [to event]}]
            (when (get event :invariant-violated)
              (let [path (find-path lts to)
                    state (get nodes to)]
                {:type :safety-violation
                 :event event
                 :path path
                 :state state})))
          edges)))

(defn- terminal-nodes
  "Find all terminal nodes in the LTS.
  Returns a set of node IDs."
  [lts]
  (->> (:edges lts)
       (filter #(get-in % [:event :terminal]))
       (map :to)
       set))

(defn- find-deadlocks
  "Find deadlocks in the LTS graph.
  A deadlock is a leaf node that is not a terminal node.
  Returns violation map or nil."
  [lts config]
  (when (not= false (:check-deadlock? config))
    (let [{:keys [edges nodes]} lts
          ;; Find all nodes that have outgoing edges
          nodes-with-outgoing (into #{} (map :from edges))
          ;; Find terminal nodes
          terminal-node-ids (terminal-nodes lts)
          ;; Find leaf nodes (no outgoing edges)
          leaf-nodes (remove nodes-with-outgoing (keys nodes))
          ;; Deadlock = leaf node that is not terminal
          deadlock-nodes (remove terminal-node-ids leaf-nodes)]
      (when-let [deadlock-id (first deadlock-nodes)]
        (let [path (find-path lts deadlock-id)
              state (get nodes deadlock-id)]
          {:type :deadlock
           :path path
           :state state})))))

(defn check
  "Model check a behavioral program for safety violations.

  Explores the state space once, checking for:
  - Safety violations (events with :invariant-violated true)
  - Deadlocks (unless :check-deadlock? is false)

  Parameters:
  - config: map with keys:
    :bthreads - the bthreads under test
    :safety-bthreads - bthreads that detect violations
    :environment-bthreads - bthreads that generate events
    :check-deadlock? - if true, detect deadlocks (default: true)
    :max-nodes - maximum nodes to explore (optional)
  Returns:
  - nil if no violations found
  - {:type :safety-violation :event event :path [events] :state state}
  - {:type :deadlock :path [events] :state state}"
  [config]
  (let [all-bthreads (assemble-all-bthreads config)
        ;; Build LTS graph
        lts-opts (cond-> {}
                   (:max-nodes config) (assoc :max-nodes (:max-nodes config)))
        lts (graph/->lts all-bthreads lts-opts)]

    ;; Check for violations in priority order
    (or (find-safety-violations lts)
        (find-deadlocks lts config))))
