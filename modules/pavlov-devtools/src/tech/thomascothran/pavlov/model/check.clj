(ns tech.thomascothran.pavlov.model.check
  "Model checking for behavioral programs.

  This namespace provides model checking capabilities for behavioral programs
  by implementing state-space exploration and graph analysis.

  The main entry point is the `check` function which builds an LTS graph
  and analyzes it for violations."
  (:require [clojure.set :as set]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.graph :as graph]))

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
                                     :path (conj path (e/type event))})
                                  neighbors)]
              (recur (into (pop queue) new-states)
                     (conj visited id)))))
        nil))))

(defn- find-safety-violations
  "Scan LTS edges for safety violations.
  Returns violation map or nil."
  [lts]
  (let [{:keys [edges nodes]} lts]
    (some (fn [{:keys [from to event]}]
            (when (get event :invariant-violated)
              (let [path (find-path lts from) ;; Path to the node BEFORE the violation
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

(defn- nodes-reaching-terminal
  "Find all nodes that can reach a terminal node.
  Uses reverse reachability from terminal nodes.
  Returns nil if computation fails (e.g., due to complex node IDs)."
  [lts]
  (try
    (let [{:keys [edges]} lts
          terminal (terminal-nodes lts)
          ;; Build reverse adjacency: node -> nodes that have edges TO it
          reverse-adj (reduce (fn [acc {:keys [from to]}]
                                (update acc to (fnil conj #{}) from))
                              {}
                              edges)]
      ;; BFS backwards from terminal nodes
      (loop [can-reach terminal
             frontier terminal
             iterations 0]
        (cond
          ;; Safety limit to prevent infinite loops
          (> iterations 10000)
          (throw (ex-info "Too many iterations in nodes-reaching-terminal" {}))

          (empty? frontier)
          can-reach

          :else
          (let [new-nodes (->> frontier
                               (mapcat reverse-adj)
                               (remove can-reach)
                               set)]
            (recur (into can-reach new-nodes) new-nodes (inc iterations))))))
    (catch Throwable _e
      ;; Return nil to signal failure - caller should handle gracefully
      nil)))

(defn- find-cycle-in-nodes
  "Find a cycle in the given set of nodes using DFS.
  Returns {:cycle-path [events] :cycle-entry-node node-id} or nil."
  [lts trapped-nodes]
  (let [{:keys [edges root]} lts
        ;; Build adjacency map for trapped nodes only
        adjacency (reduce (fn [acc {:keys [from to event]}]
                            (if (and (trapped-nodes from) (trapped-nodes to))
                              (update acc from (fnil conj []) {:to to :event event})
                              acc))
                          {}
                          edges)

        ;; DFS with path tracking and depth limit
        ;; path-events: vector of events taken so far
        ;; visited-in-path: map from node-id to index in path where we first visited it
        max-depth (* 2 (count trapped-nodes)) ;; Limit depth to prevent stack overflow
        dfs (fn dfs [node path-events visited-in-path visited-fully depth]
              (cond
                ;; Depth limit exceeded
                (> depth max-depth)
                nil

                ;; Found a cycle!
                (contains? visited-in-path node)
                (let [cycle-start-idx (get visited-in-path node)]
                  {:cycle-path (subvec path-events cycle-start-idx)
                   :cycle-entry-node node})

                ;; Already explored this node fully
                (visited-fully node)
                nil

                ;; Continue DFS
                :else
                (let [neighbors (get adjacency node [])
                      current-idx (count path-events)]
                  (some (fn [{:keys [to event]}]
                          (let [event-val (e/type event)]
                            (dfs to
                                 (conj path-events event-val)
                                 (assoc visited-in-path node current-idx)
                                 (conj visited-fully node)
                                 (inc depth))))
                        neighbors))))]

    ;; Start DFS from root if it's trapped, otherwise from any trapped node
    (cond
      (trapped-nodes root)
      (dfs root [] {} #{} 0)

      (seq trapped-nodes)
      (some (fn [start-node]
              (dfs start-node [] {} #{} 0))
            trapped-nodes)

      :else
      nil)))

(defn- find-livelocks
  "Find livelocks in the LTS graph.
  A livelock is a cycle where no node can reach a terminal state."
  [lts config]
  (when (not= false (:check-livelock? config))
    ;; Early exit if graph is empty or has no edges
    (when (and (seq (:nodes lts)) (seq (:edges lts)))
      (when-let [can-reach-terminal (nodes-reaching-terminal lts)]
        ;; Only proceed if nodes-reaching-terminal succeeded
        (let [;; Trapped nodes = all nodes - can-reach-terminal
              all-nodes (set (keys (:nodes lts)))
              trapped-nodes (set/difference all-nodes can-reach-terminal)]

          ;; If there are trapped nodes, look for cycles
          (when (seq trapped-nodes)
            (when-let [{:keys [cycle-path cycle-entry-node]}
                       (find-cycle-in-nodes lts trapped-nodes)]
              {:type :livelock
               :path (or (find-path lts cycle-entry-node) [])
               :cycle cycle-path})))))))

(defn- find-path-with-full-events
  "Find a path from root to target node, returning full event maps.
  Used by liveness checking where predicates need access to full event data.
  Returns a vector of event maps, or nil if no path exists."
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
                                     :path (conj path event)}) ;; Full event map
                                  neighbors)]
              (recur (into (pop queue) new-states)
                     (conj visited id)))))
        nil))))

(defn- check-universal-liveness
  "Check a universal liveness property: all paths must satisfy the predicate.
  Returns a violation if ANY path fails the predicate."
  [lts property-key {:keys [predicate eventually]} terminal-node-ids]
  (or
   ;; First check terminating paths
   (some (fn [terminal-node-id]
           (let [full-event-trace (find-path-with-full-events lts terminal-node-id)
                 keyword-trace (find-path lts terminal-node-id)]
             ;; If predicate returns falsy, this is a violation
             (when-not (predicate full-event-trace)
               {:type :liveness-violation
                :property property-key
                :quantifier :universal
                :trace keyword-trace})))
         terminal-node-ids)

   ;; Then check trapped cycles
   (when-let [can-reach (nodes-reaching-terminal lts)]
     (let [all-nodes (set (keys (:nodes lts)))
           trapped (set/difference all-nodes can-reach)]
       (when (seq trapped)
         (when-let [{:keys [cycle-path]} (find-cycle-in-nodes lts trapped)]
           ;; For cycles with :eventually, check if any event in cycle matches
           ;; The cycle-path contains keywords, so we check directly
           (when eventually
             (when-not (some #(contains? eventually %) cycle-path)
               {:type :liveness-violation
                :property property-key
                :quantifier :universal
                :cycle cycle-path}))))))))

(defn- check-existential-liveness
  "Check an existential liveness property: at least one path must satisfy the predicate.
  Returns a violation if NO path satisfies the predicate."
  [lts property-key {:keys [predicate eventually]} terminal-node-ids]
  (let [;; Check terminating paths
        terminating-path-satisfies?
        (some (fn [terminal-node-id]
                (let [full-event-trace (find-path-with-full-events lts terminal-node-id)]
                  (predicate full-event-trace)))
              terminal-node-ids)

        ;; Check if any cycle satisfies the predicate
        cycle-satisfies?
        (when eventually
          (when-let [can-reach (nodes-reaching-terminal lts)]
            (let [all-nodes (set (keys (:nodes lts)))
                  trapped (set/difference all-nodes can-reach)]
              (when (seq trapped)
                (when-let [{:keys [cycle-path]} (find-cycle-in-nodes lts trapped)]
                  ;; For cycles with :eventually, check if any event in cycle matches
                  (some #(contains? eventually %) cycle-path))))))]

    ;; Return violation if neither terminating paths nor cycles satisfy
    (when-not (or terminating-path-satisfies? cycle-satisfies?)
      {:type :liveness-violation
       :property property-key
       :quantifier :existential})))

(defn- find-liveness-violations
  "Check liveness properties against the LTS graph.
  For universal quantifier: returns violation if ANY path fails the predicate.
  For existential quantifier: returns violation if NO path satisfies the predicate.

  Checks both terminating paths and trapped cycles.

  Supports :eventually shorthand: instead of providing a :predicate function,
  you can provide :eventually with a set of event types. This creates a predicate
  that checks if any event in the trace has a type in the :eventually set."
  [lts config]
  (when-let [liveness-props (:liveness config)]
    (let [terminal-node-ids (terminal-nodes lts)]
      ;; Check each liveness property
      (some (fn [[property-key prop]]
              (let [{:keys [quantifier predicate eventually]} prop
                    ;; Transform :eventually to :predicate
                    predicate (or predicate
                                  (when eventually
                                    (fn [trace]
                                      (some #(contains? eventually (e/type %)) trace))))
                    ;; Create prop with predicate for helpers
                    prop-with-predicate (assoc prop :predicate predicate)]
                (case quantifier
                  :universal
                  (check-universal-liveness lts property-key prop-with-predicate terminal-node-ids)

                  :existential
                  (check-existential-liveness lts property-key prop-with-predicate terminal-node-ids))))
            liveness-props))))

(defn check
  "Model check a behavioral program for safety violations.

  Explores the state space once, checking for:
  - Livelocks (cycles with no path to terminal, unless :check-livelock? is false)
  - Liveness violations (properties not satisfied on paths, when :liveness is provided)
  - Safety violations (events with :invariant-violated true)
  - Deadlocks (unless :check-deadlock? is false)

  Parameters:
  - config: map with keys:
    :bthreads - the bthreads under test
    :safety-bthreads - bthreads that detect violations
    :environment-bthreads - bthreads that generate events
    :check-livelock? - if true, detect livelocks (default: true)
    :check-deadlock? - if true, detect deadlocks (default: true)
    :liveness - map of liveness properties to check (optional)
    :max-nodes - maximum nodes to explore (optional)
  Returns:
  - nil if no violations found
  - {:type :livelock :path [events] :cycle [events]}
  - {:type :liveness-violation :property <key> :quantifier <q> :trace [events]}
  - {:type :safety-violation :event event :path [events] :state state}
  - {:type :deadlock :path [events] :state state}
  - {:type :truncated :max-nodes N :message \"...\"} if exploration was truncated"
  [config]
  (let [all-bthreads (assemble-all-bthreads config)
        ;; Build LTS graph
        lts-opts (cond-> {}
                   (:max-nodes config) (assoc :max-nodes (:max-nodes config)))
        lts (graph/->lts all-bthreads lts-opts)
        truncated? (:truncated lts)]

    ;; Check for violations in priority order: livelock > liveness > safety > deadlock
    ;; Wrap livelock check in try-catch to handle edge cases like circular node IDs
    (let [violation (or (try
                          (find-livelocks lts config)
                          (catch StackOverflowError _e
                            ;; Skip livelock detection if it causes stack overflow
                            ;; (This can happen with complex node IDs containing error objects)
                            nil))
                        (find-liveness-violations lts config)
                        (find-safety-violations lts)
                        ;; Don't report deadlocks if truncated (likely false positives)
                        (when-not truncated?
                          (find-deadlocks lts config)))]
      (cond
        ;; Real violation found
        (and violation truncated?)
        (assoc violation :truncated true)

        violation
        violation

        ;; No violation but graph was truncated
        truncated?
        {:type :truncated
         :max-nodes (:max-nodes config)
         :message "State space exploration truncated. Results may be incomplete."}

        ;; No violation, no truncation
        :else nil))))
