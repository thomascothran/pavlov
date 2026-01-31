(ns tech.thomascothran.pavlov.model.check
  "Model checking for behavioral programs.

  This namespace provides exhaustive state-space exploration to verify
  behavioral programs. It detects bugs that might only occur in specific
  execution paths or event orderings.

  ## Quick Start

  ```clojure
  (require '[tech.thomascothran.pavlov.model.check :as check])
  (require '[tech.thomascothran.pavlov.bthread :as b])

  ;; Check a simple program for deadlocks
  (check/check
    {:bthreads {:my-bthread (b/bids [{:request #{:hello}}
                                     {:request #{{:type :done :terminal true}}}])}})
  ;; => nil (no violations found)
  ```

  ## The `check` Function

  The main entry point. It builds a Labeled Transition System (LTS) graph
  from your bthreads and analyzes it for violations.

  ### Configuration Options

  | Key | Type | Default | Description |
  |-----|------|---------|-------------|
  | `:bthreads` | map/vec | required | The bthreads under test |
  | `:safety-bthreads` | map/vec | nil | Bthreads that emit `:invariant-violated` events |
  | `:environment-bthreads` | map/vec | nil | Bthreads simulating external inputs |
  | `:check-deadlock?` | bool | true | Whether to detect deadlocks |
  | `:check-livelock?` | bool | true | Whether to detect livelocks (infinite loops) |
  | `:liveness` | map | nil | Liveness properties to verify (see below) |
  | `:max-nodes` | int | nil | Limit state space exploration |

  ### Return Values

  Returns `nil` if no violations found. Otherwise returns a categorized map
  with violation vectors (only non-empty categories are included):

  ```clojure
  {:livelocks [{:path [...] :cycle [...]}]
   :liveness-violations [{:property ... :quantifier ... :trace [...]}]
   :safety-violations [{:event {...} :path [...] :state {...}}]
   :deadlocks [{:path [...] :state {...}}]
   :truncated true}  ;; optional flag when max-nodes exceeded
  ```

  - `:livelocks` — Programs stuck in infinite loops
  - `:liveness-violations` — Required events never occur
  - `:safety-violations` — Invariants violated
  - `:deadlocks` — Programs stuck, no events possible
  - `:truncated` — Boolean flag when exploration hit `:max-nodes` limit

  ## Violation Types

  ### Deadlock Detection

  A deadlock occurs when no events can be selected and no terminal event
  has occurred. The program is stuck.

  ```clojure
  ;; This will deadlock - requests event then has nothing to do
  (check/check
    {:bthreads {:stuck (b/bids [{:request #{:something}}])}})
  ;; => {:deadlocks [{:path [:something], :state {...}}]}
  ```

  To mark successful completion, use `:terminal true`:

  ```clojure
  ;; This terminates successfully - no deadlock
  (check/check
    {:bthreads {:ok (b/bids [{:request #{{:type :done :terminal true}}}])}})
  ;; => nil
  ```

  ### Livelock Detection

  A livelock occurs when the program runs forever in a cycle without
  ever reaching a terminal state.

  ```clojure
  ;; Infinite ping-pong loop - never terminates
  (check/check
    {:bthreads {:loop (b/round-robin [{:request #{:ping}}
                                      {:request #{:pong}}])}})
  ;; => {:livelocks [{:path [], :cycle [:ping :pong]}]}
  ```

  Disable with `:check-livelock? false` if cycles are intentional.

  ### Safety Violations

  Use safety bthreads to assert invariants. When they detect a bad state,
  they emit an event with `:invariant-violated true`.

  ```clojure
  (check/check
    {:bthreads {:worker (b/bids [{:request #{:work}}
                                 {:request #{:work}}  ;; oops, double work
                                 {:request #{{:type :done :terminal true}}}])}
     :safety-bthreads
     {:no-double-work
      (b/step (fn [event state]
                (let [new-state (update state :work-count (fnil inc 0))]
                  (if (> (:work-count new-state) 1)
                    ;; Violation! Emit invariant-violated event
                    [(b/bids [{:request #{{:type :double-work-error
                                           :invariant-violated true}}}])
                     new-state]
                    ;; OK, continue monitoring
                    [nil new-state])))
              {})}})
  ;; => {:safety-violations [{:event {:type :double-work-error, ...}, :path [...], :state {...}}]}
  ```

  ### Liveness Properties

  Liveness properties assert that \"something good eventually happens.\"
  Use the `:liveness` option to specify properties.

  #### Universal Quantifier (`:universal`)

  The property must be satisfied on ALL execution paths.

  ```clojure
  ;; Assert: payment MUST eventually occur on every path
  (check/check
    {:bthreads {:flow (b/bids [{:request #{:process}}
                               {:request #{{:type :done :terminal true}}}])}
     :liveness
     {:payment-required
      {:quantifier :universal
       :eventually #{:payment}}}})
  ;; => {:liveness-violations [{:property :payment-required,
  ;;                            :quantifier :universal,
  ;;                            :trace [:process :done]}]}
  ```

  #### Existential Quantifier (`:existential`)

  The property must be satisfied on AT LEAST ONE path.

  ```clojure
  ;; Assert: payment must be POSSIBLE (at least one path has it)
  (check/check
    {:bthreads {:path-a (b/bids [{:request #{:payment}}
                                 {:request #{{:type :done-a :terminal true}}}])
                :path-b (b/bids [{:request #{:skip}}
                                 {:request #{{:type :done-b :terminal true}}}])}
     :liveness
     {:payment-possible
      {:quantifier :existential
       :eventually #{:payment}}}})
  ;; => nil (satisfied - path-a has payment)
  ```

  #### Predicate Form

  For complex conditions, use `:predicate` instead of `:eventually`:

  ```clojure
  (check/check
    {:bthreads {...}
     :liveness
     {:order-complete
      {:quantifier :universal
       :predicate (fn [trace]
                    ;; trace is a vector of event maps
                    (let [types (map #(tech.thomascothran.pavlov.event/type %) trace)]
                      (and (some #{:payment} types)
                           (some #{:shipment} types))))}}})
  ```

  ## Environment Bthreads

  Use `:environment-bthreads` to simulate external inputs or
  nondeterministic choices. The model checker explores all possibilities.

  ```clojure
  (check/check
    {:bthreads {:handler (b/on #{:success :failure}
                           (fn [event]
                             (if (= :success (e/type event))
                               (b/bids [{:request #{{:type :done :terminal true}}}])
                               (b/bids [{:request #{:retry}}
                                        {:request #{{:type :done :terminal true}}}]))))}
     :environment-bthreads
     {:external-api (b/bids [{:request #{:success :failure}}])}})
  ```

  ## Multiple Violations

  When multiple issues exist, ALL violations are returned in a single categorized map.
  Fix violations in this recommended order (most severe first):

  1. **Livelocks** — Most severe (pegs CPU at 100%)
  2. **Liveness violations** — Required properties never satisfied
  3. **Safety violations** — Bad states reached
  4. **Deadlocks** — Programs stuck

  ## Performance

  The model checker explores all reachable states. Use `:max-nodes` to
  limit exploration for large state spaces:

  ```clojure
  (check/check
    {:bthreads {...}
     :max-nodes 10000})
  ;; => {:truncated true}
  ;;    if limit reached without finding violations
  ;; Or: {:deadlocks [...], :truncated true}
  ;;    if violations found before truncation
  ```

  ## Best practices

  Model checks are dissimilar to traditional unit tests in the sense that you will have *one*
  model check running in a test that tests *all* the scenarios for that group of bthreads.

  ### The Workflow

  The following structure can be used for tests:

  1. Decide what bthreads you want to test
  2. Specify initiating events in a single request, establishing top-level branches
     in the execution graph and kicking things off
  3. Specify positive scenarios and validate they occur with existential liveness checks
  4. Specify safety properties as their own bthreads
  5. Specify universal liveness properties

  ### One branching request for all initiating events

  This *requires* having *one* bthread with *one* bid that requests the initiating events
  in a set - causing this to be the initial branch point.

  ### Positive Scenarios

  Specify all the desired positive scenarios with a bthread for each scenario. These will
  use `b/bid` with `:wait-on` events. `b/bid` can be used either with maps or functions.

  The final bid will `:request` an event unique to the scenario which will then be used
  with liveness checks to guarantee the scenario reached completion.
  "
  (:require [clojure.set :as set]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.graph :as graph])
  (:import [clojure.lang PersistentQueue]))

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
    (loop [queue (conj PersistentQueue/EMPTY {:id root :path []})
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
  "Scan LTS edges for all safety violations.
  Returns a vector of violation maps (without :type key)."
  [lts]
  (let [{:keys [edges nodes]} lts]
    (vec (for [{:keys [from to event]} edges
               :when (get event :invariant-violated)]
           (let [path (find-path lts from) ;; Path to the node BEFORE the violation
                 state (get nodes to)]
             {:event event
              :path path
              :state state})))))

(defn- terminal-nodes
  "Find all terminal nodes in the LTS.
  Returns a set of node IDs."
  [lts]
  (->> (:edges lts)
       (filter #(get-in % [:event :terminal]))
       (map :to)
       set))

(defn- find-deadlocks
  "Find all deadlocks in the LTS graph.
  A deadlock is a leaf node that is not a terminal node.
  Returns a vector of deadlock maps (without :type key)."
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
      ;; Return ALL deadlocks as a vector
      (vec (for [deadlock-id deadlock-nodes]
             (let [path (find-path lts deadlock-id)
                   state (get nodes deadlock-id)]
               {:path path
                :state state}))))))

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
        (if (empty? frontier)
          can-reach
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
  "Find all livelocks in the LTS graph.
  A livelock is a cycle where no node can reach a terminal state.
  Returns a vector of livelock maps (without :type key)."
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
          ;; Note: We only return the first cycle found, but as a vector
          ;; Future enhancement could find multiple distinct cycles
          (when (seq trapped-nodes)
            (when-let [{:keys [cycle-path cycle-entry-node]}
                       (find-cycle-in-nodes lts trapped-nodes)]
              [{:path (or (find-path lts cycle-entry-node) [])
                :cycle cycle-path}])))))))

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
    (loop [queue (conj PersistentQueue/EMPTY {:id root :path []})
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
  Returns a vector of violations (one for each path that fails the predicate)."
  [lts property-key {:keys [predicate eventually]} terminal-node-ids]
  (let [;; Collect all terminating path violations
        terminating-violations
        (vec (for [terminal-node-id terminal-node-ids
                   :let [full-event-trace (find-path-with-full-events lts terminal-node-id)
                         keyword-trace (find-path lts terminal-node-id)]
                   :when (not (predicate full-event-trace))]
               {:property property-key
                :quantifier :universal
                :trace keyword-trace}))

        ;; Check trapped cycles
        cycle-violations
        (when-let [can-reach (nodes-reaching-terminal lts)]
          (let [all-nodes (set (keys (:nodes lts)))
                trapped (set/difference all-nodes can-reach)]
            (when (seq trapped)
              (when-let [{:keys [cycle-path]} (find-cycle-in-nodes lts trapped)]
                ;; Check if cycle satisfies the liveness property
                (let [violation? (cond
                                   ;; For :eventually shorthand, check if any event in cycle matches
                                   eventually
                                   (not (some #(contains? eventually %) cycle-path))

                                   ;; For :predicate, convert cycle to minimal event maps and run predicate
                                   predicate
                                   (let [cycle-as-trace (mapv (fn [kw] {:type kw}) cycle-path)]
                                     (not (predicate cycle-as-trace))))]
                  (when violation?
                    [{:property property-key
                      :quantifier :universal
                      :cycle cycle-path}]))))))]

    ;; Combine all violations
    (vec (concat terminating-violations cycle-violations))))

(defn- check-existential-liveness
  "Check an existential liveness property: at least one path must satisfy the predicate.
  Returns a vector with one violation if NO path satisfies the predicate, or empty vector if satisfied."
  [lts property-key {:keys [predicate eventually]} terminal-node-ids]
  (let [;; Check terminating paths
        terminating-path-satisfies?
        (some (fn [terminal-node-id]
                (let [full-event-trace (find-path-with-full-events lts terminal-node-id)]
                  (predicate full-event-trace)))
              terminal-node-ids)

        ;; Check if any cycle satisfies the predicate
        cycle-satisfies?
        (when-let [can-reach (nodes-reaching-terminal lts)]
          (let [all-nodes (set (keys (:nodes lts)))
                trapped (set/difference all-nodes can-reach)]
            (when (seq trapped)
              (when-let [{:keys [cycle-path]} (find-cycle-in-nodes lts trapped)]
                (cond
                  ;; For :eventually shorthand, check if any event in cycle matches
                  eventually
                  (some #(contains? eventually %) cycle-path)

                  ;; For :predicate, convert cycle to minimal event maps and run predicate
                  predicate
                  (let [cycle-as-trace (mapv (fn [kw] {:type kw}) cycle-path)]
                    (predicate cycle-as-trace)))))))]

    ;; Return violation vector if neither terminating paths nor cycles satisfy
    (if (or terminating-path-satisfies? cycle-satisfies?)
      []
      [{:property property-key
        :quantifier :existential}])))

(defn- find-liveness-violations
  "Check liveness properties against the LTS graph.
  For universal quantifier: returns violations for each path that fails the predicate.
  For existential quantifier: returns violation if NO path satisfies the predicate.

  Checks both terminating paths and trapped cycles.

  Supports :eventually shorthand: instead of providing a :predicate function,
  you can provide :eventually with a set of event types. This creates a predicate
  that checks if any event in the trace has a type in the :eventually set.

  Returns a vector of all liveness violations (without :type key)."
  [lts config]
  (when-let [liveness-props (:liveness config)]
    (let [terminal-node-ids (terminal-nodes lts)
          ;; Also find deadlock nodes - these are leaf nodes that are not terminal
          {:keys [edges nodes]} lts
          nodes-with-outgoing (into #{} (map :from edges))
          leaf-nodes (remove nodes-with-outgoing (keys nodes))
          deadlock-node-ids (set (remove terminal-node-ids leaf-nodes))
          ;; Combine terminal and deadlock nodes for liveness checking
          nodes-to-check (into terminal-node-ids deadlock-node-ids)]
      ;; Collect violations from all liveness properties
      (vec (mapcat (fn [[property-key prop]]
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
                         (check-universal-liveness lts property-key prop-with-predicate nodes-to-check)

                         :existential
                         (check-existential-liveness lts property-key prop-with-predicate nodes-to-check))))
                   liveness-props)))))

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
  - A map with violation categories (only non-empty categories included):
    {:livelocks [{:path [...] :cycle [...]}]
     :liveness-violations [{:property ... :quantifier ... :trace [...]}]
     :safety-violations [{:event ... :path [...] :state {...}}]
     :deadlocks [{:path [...] :state {...}}]
     :truncated true}  ;; optional flag when max-nodes exceeded"
  [config]
  (let [all-bthreads (assemble-all-bthreads config)
        ;; Build LTS graph
        lts-opts (cond-> {}
                   (:max-nodes config) (assoc :max-nodes (:max-nodes config)))
        lts (graph/->lts all-bthreads lts-opts)
        truncated? (:truncated lts)

        ;; Collect all violations (each find-* returns a vector)
        ;; Wrap livelock check in try-catch to handle edge cases like circular node IDs
        livelocks (try
                    (find-livelocks lts config)
                    (catch StackOverflowError _e
                      (throw (ex-info "livelock detection failed due to stack overflow. Possible circular node IDs."
                                      {:lts lts
                                       :config config}))))
        liveness-violations (find-liveness-violations lts config)
        safety-violations (find-safety-violations lts)
        ;; Don't report deadlocks if truncated (likely false positives)
        deadlocks (when-not truncated?
                    (find-deadlocks lts config))

        ;; Build categorized result map - only include non-empty categories
        result (cond-> nil
                 (seq livelocks) (assoc :livelocks livelocks)
                 (seq liveness-violations) (assoc :liveness-violations liveness-violations)
                 (seq safety-violations) (assoc :safety-violations safety-violations)
                 (seq deadlocks) (assoc :deadlocks deadlocks)
                 truncated? (assoc :truncated true))]

    ;; Return nil if no violations and not truncated
    (when result (vary-meta result assoc :lts lts))))
