(ns tech.thomascothran.pavlov.graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.nav :as pnav]
            [tech.thomascothran.pavlov.bthread :as b]))

(defn make-bthreads-two-step
  []
  {:first (b/bids [{:request #{:a}}
                   {:request #{:b}}])})

(defn make-branching-bthreads
  []
  [[:chooser (b/bids [{:request #{:branch/a :branch/b :branch/c}}])]
   [:branch-b (b/on :branch/b (constantly {:request #{:branch/b-1}}))]
   [:branch-c-advance (b/on :branch/c (constantly {:request #{:branch/c-1}}))]
   [:branch-c-finish (b/on :branch/c-1 (constantly {:request #{:branch/c-2}}))]])

(defn make-bthreads-simple-linear
  "Create a single bthread that requests :a then :b then completes."
  []
  {:simple (b/bids [{:request #{:a}}
                    {:request #{:b}}])})

(defn make-bthreads-simple-branch
  "Create a bthread that requests either :a or :b (branching choice)."
  []
  {:chooser (b/bids [{:request #{:a :b}}])})

(defn make-bthreads-branch-and-converge
  "Create bthreads that branch on :a/:b then converge to same state."
  []
  {:brancher (b/bids [{:request #{:a :b}}]) ;; offers :a or :b
   :finisher (b/bids [{:wait-on #{:a :b}} ;; waits for either
                      {:request #{:done}}])})

(defn make-bthreads-post-convergence-branch
  "Branch, converge, then branch again."
  []
  {:brancher (b/bids [{:request #{:a :b}}]) ;; first branch
   :post-converge (b/bids [{:wait-on #{:a :b}} ;; wait for first branch
                           {:request #{:x :y}}])})

(defn make-bthreads-cycle
  "Create a bthread that infinitely loops requesting :a."
  []
  {:looper (b/repeat {:request #{:a}})})

(defn make-bthreads-many-steps
  "Create a bthread that goes through 20 sequential steps."
  []
  (let [steps (vec (for [i (range 20)]
                     (if (even? i)
                       {:request #{:a}}
                       {:request #{:b}})))]
    {:stepper (b/bids steps)}))

(defn make-lts-losing-edges-bug-repro
  "Minimal reproduction case for the LTS node/edge consistency bug.

  This scenario includes:
  - A stateful bthread that accumulates state
  - A blocked terminal event from another bthread
  - The stateful bthread requests a non-blocked event that should be selectable

  The bug manifests when edge identifiers reference nodes that don't exist
  in the :nodes map."
  []
  {:init
   (b/bids [{:request #{{:type :start}}}])

   :stateful-worker
   (let [default-bid {:wait-on #{:work-done}}]
     (b/thread [state event]
       :pavlov/init
       [nil default-bid]
       :work-done
       [(inc (or state 0)) {:request #{{:type :work-result}}}]
       [state default-bid]))

   :do-work
   (b/on :start (fn [_] {:request #{{:type :work-done}}}))

   :terminal-requester
   (b/on :start (fn [_] {:request #{{:type :blocked-terminal :terminal true}}}))

   :blocker
   {:block #{:blocked-terminal}}

   :test-terminal
   (b/bids [{:wait-on #{:start}}
            {:wait-on #{:work-done}}
            {:wait-on #{:work-result}}
            {:request #{{:type :test-passed :terminal true}}}])})

(comment
  (tap> (pnav/root (make-branching-bthreads))))

(deftest lts-simple-linear-flow
  (testing "LTS has edges for :a and :b events in sequence"
    (let [lts (graph/->lts (make-bthreads-simple-linear))
          edges (:edges lts)
          a-edge (first (filter #(= :a (:event %)) edges))
          b-edge (first (filter #(= :b (:event %)) edges))]
      (is (some? a-edge)
          "Should have an edge for event :a")
      (is (some? b-edge)
          "Should have an edge for event :b")
      (is (= (:to a-edge) (:from b-edge))
          "The :a edge should connect to the :b edge (two-step flow)")))

  (testing "LTS has nodes map with all edge endpoints"
    (let [lts (graph/->lts (make-bthreads-simple-linear))
          nodes (:nodes lts)
          edges (:edges lts)
          a-edge (first (filter #(= :a (:event %)) edges))
          b-edge (first (filter #(= :b (:event %)) edges))]
      (is (map? nodes)
          "Should have a :nodes map")
      (is (contains? nodes (:from a-edge))
          "The :from node of edge :a should exist in :nodes")
      (is (contains? nodes (:to a-edge))
          "The :to node of edge :a should exist in :nodes")
      (is (contains? nodes (:to b-edge))
          "The :to node of edge :b should exist in :nodes")))

  (testing "LTS node values contain minimal state data"
    (let [lts (graph/->lts (make-bthreads-simple-linear))
          nodes (:nodes lts)
          edges (:edges lts)
          a-edge (first (filter #(= :a (:event %)) edges))
          ;; Get a node value to inspect
          node-value (get nodes (:to a-edge))]

      (is (map? node-value)
          "Node value should be a map")

      (is (not (empty? node-value))
          "Node value should not be empty")

      ;; Verify required keys are present
      (is (contains? node-value :bthread->bid)
          "Node should contain :bthread->bid key")

      (is (contains? node-value :bthreads-by-priority)
          "Node should contain :bthreads-by-priority key")

      (is (contains? node-value :saved-bthread-states)
          "Node should contain :saved-bthread-states key")

      (is (contains? node-value :requests)
          "Node should contain :requests key (derived map)")

      (is (contains? node-value :waits)
          "Node should contain :waits key (derived map)")

      (is (contains? node-value :blocks)
          "Node should contain :blocks key (derived map)")

      ;; Verify unwanted keys are NOT present (flattened structure)
      (is (not (contains? node-value :path))
          "Node should NOT contain :path key (multiple paths can lead to same node)")

      (is (not (contains? node-value :last-event))
          "Node should NOT contain :last-event key")

      (is (not (contains? node-value :next-event))
          "Node should NOT contain :next-event key")

      (is (not (contains? node-value :name->bthread))
          "Node should NOT contain :name->bthread key")

      (is (not (contains? node-value :bprogram/state))
          "Node should NOT contain :bprogram/state key (structure is now flattened)")))

  (testing "LTS has a :root key identifying the initial state"
    (let [lts (graph/->lts (make-bthreads-simple-linear))
          nodes (:nodes lts)
          edges (:edges lts)
          root (:root lts)]

      (is (some? root)
          "LTS should have a :root key")

      (is (contains? nodes root)
          "The :root value should be one of the node identifiers")

      (is (not-any? #(= root (:to %)) edges)
          "The :root node should have no incoming edges"))))

(deftest lts-simple-branch-flow
  (testing "LTS has branching edges for :a and :b from same state"
    (let [lts (graph/->lts (make-bthreads-simple-branch))
          edges (:edges lts)
          a-edge (first (filter #(= :a (:event %)) edges))
          b-edge (first (filter #(= :b (:event %)) edges))]
      (is (some? a-edge)
          "Should have an edge for event :a")
      (is (some? b-edge)
          "Should have an edge for event :b")
      (is (= (:from a-edge) (:from b-edge))
          "Both :a and :b edges should branch from the same state"))))

(deftest lts-branches-on-unordered-bthreads
  (testing "LTS should branch on unordered bthreads"
    (let [bthreads {:a (b/bids [{:request #{:a}}])
                    :b (b/bids [{:request #{:b}}])}
          lts (graph/->lts bthreads)]
      (is (= 4 (count (:edges lts)))))))

(deftest lts-branch-and-converge
  (testing "LTS branches on :a/:b then converges to same state"
    (let [lts (graph/->lts (make-bthreads-branch-and-converge))
          edges (:edges lts)
          a-edge (first (filter #(= :a (:event %)) edges))
          b-edge (first (filter #(= :b (:event %)) edges))
          done-edge (first (filter #(= :done (:event %)) edges))]

      ;; Verify all edges exist
      (is (some? a-edge)
          "Should have an edge for event :a")
      (is (some? b-edge)
          "Should have an edge for event :b")
      (is (some? done-edge)
          "Should have an edge for event :done")

      ;; Verify branching from same state
      (is (= (:from a-edge) (:from b-edge))
          "Both :a and :b edges should branch from the same initial state")

      ;; Verify convergence to same state (KEY assertion!)
      (is (= (:to a-edge) (:to b-edge))
          "Both :a and :b edges should converge to the same state")

      ;; Verify :done edge connects through converged state
      (is (= (:from done-edge) (:to a-edge))
          "The :done edge should originate from the converged state"))))

(deftest lts-post-convergence-branching
  (testing "LTS branches, converges, then branches again"
    (let [lts (graph/->lts (make-bthreads-post-convergence-branch))
          edges (:edges lts)
          a-edge (first (filter #(= :a (:event %)) edges))
          b-edge (first (filter #(= :b (:event %)) edges))
          x-edge (first (filter #(= :x (:event %)) edges))
          y-edge (first (filter #(= :y (:event %)) edges))]

      ;; Verify first branch edges exist
      (is (some? a-edge)
          "Should have an edge for event :a")
      (is (some? b-edge)
          "Should have an edge for event :b")

      ;; Verify :a and :b converge to same state
      (is (= (:to a-edge) (:to b-edge))
          "Both :a and :b edges should converge to the same state")

      ;; Verify post-convergence branch edges exist
      (is (some? x-edge)
          "Should have an edge for event :x")
      (is (some? y-edge)
          "Should have an edge for event :y")

      ;; Verify :x and :y branch from the converged state
      (is (= (:from x-edge) (:to a-edge))
          "The :x edge should branch from the converged state")
      (is (= (:from y-edge) (:to a-edge))
          "The :y edge should branch from the converged state (same as :x)")
      (is (= (:from x-edge) (:from y-edge))
          "Both :x and :y should branch from the same converged state"))))

(deftest lts-cycle
  (testing "LTS completes without hanging on cyclic bthread"
    (let [lts (graph/->lts (make-bthreads-cycle))]
      (is (some? lts)
          "LTS should complete and return a result")))

  (testing "LTS has a self-loop edge for repeated event"
    (let [lts (graph/->lts (make-bthreads-cycle))
          edges (:edges lts)
          a-edges (filter #(= :a (:event %)) edges)]
      (is (seq a-edges)
          "Should have at least one edge for event :a")

      ;; Check if any edge is a self-loop
      (let [self-loop (first (filter #(= (:from %) (:to %)) a-edges))]
        (is (some? self-loop)
            "Should have a self-loop edge where :from equals :to"))))

  (testing "LTS cycle creates minimal node set"
    (let [lts (graph/->lts (make-bthreads-cycle))
          nodes (:nodes lts)]
      (is (map? nodes)
          "Should have a :nodes map")
      ;; With a pure cycle, we should have a very small number of nodes
      ;; (initial state + one or two states in the cycle)
      (is (<= (count nodes) 3)
          "Cyclic bthread should create minimal nodes (not infinitely many)"))))

(deftest lts-max-nodes-limit
  (testing "LTS respects :max-nodes limit"
    (let [lts (graph/->lts (make-bthreads-many-steps) {:max-nodes 10})
          nodes (:nodes lts)]
      (is (<= (count nodes) 10)
          "Should not exceed max-nodes limit")))

  (testing "LTS returns :truncated true when limit is hit"
    (let [lts (graph/->lts (make-bthreads-many-steps) {:max-nodes 10})]
      (is (= true (:truncated lts))
          "Should indicate truncation when max-nodes limit is hit")))

  (testing "LTS returns :truncated false when limit is not hit"
    (let [lts (graph/->lts (make-bthreads-simple-linear))] ;; small graph, no limit
      (is (= false (:truncated lts))
          "Should indicate no truncation when exploration completes normally"))))

(deftest lts-node-edge-consistency
  (testing "All edge :from identifiers exist in :nodes"
    (let [lts (graph/->lts (make-lts-losing-edges-bug-repro))
          nodes (:nodes lts)
          edges (:edges lts)
          from-ids (set (map :from edges))
          missing-from (remove #(contains? nodes %) from-ids)]
      (is (empty? missing-from)
          (str "Edge :from identifiers not found in :nodes: " (vec missing-from)))))

  (testing "All edge :to identifiers exist in :nodes"
    (let [lts (graph/->lts (make-lts-losing-edges-bug-repro))
          nodes (:nodes lts)
          edges (:edges lts)
          to-ids (set (map :to edges))
          missing-to (remove #(contains? nodes %) to-ids)]
      (is (empty? missing-to)
          (str "Edge :to identifiers not found in :nodes: " (vec missing-to)))))

  (testing "The :root identifier exists in :nodes"
    (let [lts (graph/->lts (make-lts-losing-edges-bug-repro))
          nodes (:nodes lts)
          root (:root lts)]
      (is (contains? nodes root)
          (str "Root identifier " root " not found in :nodes"))))

  (testing "Node identifiers in edges match node identifiers in :nodes (identity check)"
    (let [lts (graph/->lts (make-lts-losing-edges-bug-repro))
          nodes (:nodes lts)
          edges (:edges lts)
          edge-ids (into #{} (concat (map :from edges) (map :to edges)))
          node-ids (set (keys nodes))
          ;; Find any edge IDs that don't appear in node-ids
          missing-ids (clojure.set/difference edge-ids node-ids)]
      (is (empty? missing-ids)
          (str "Edge identifiers not found in :nodes map keys: " (vec missing-ids)))))

  (testing "Every node with outgoing edges is correctly identified as non-leaf"
    ;; This test replicates the deadlock detection logic to verify
    ;; that the LTS graph is consistent for model checking
    (let [lts (graph/->lts (make-lts-losing-edges-bug-repro))
          {:keys [edges nodes]} lts
          ;; Find all nodes that have outgoing edges (as deadlock detection does)
          nodes-with-outgoing (into #{} (map :from edges))
          ;; Find terminal nodes
          terminal-node-ids (->> edges
                                 (filter #(get-in % [:event :terminal]))
                                 (map :to)
                                 set)
          ;; Find leaf nodes (no outgoing edges according to edge :from values)
          leaf-nodes (remove nodes-with-outgoing (keys nodes))
          ;; Deadlock = leaf node that is not terminal
          deadlock-nodes (remove terminal-node-ids leaf-nodes)]
      ;; In a correctly functioning program that terminates successfully,
      ;; there should be no false deadlocks
      (is (empty? deadlock-nodes)
          (str "False deadlock detected due to LTS node/edge inconsistency. "
               "Deadlock nodes: " (count deadlock-nodes) ". "
               "This indicates edge :from identifiers don't match node keys.")))))

(deftest test-graph-with-different-bthread-constructors-doesnt-throw
  (testing "if the state of a bthread is unserializable, it throws"
    (let [plain-map {:block #{:z}}
          bids (b/bids [{:request #{:a}}
                        (fn [_] {:request
                                 #{{:type :b}}})])
          round-robin
          (b/round-robin
           [(b/on :a (fn [_] {:request #{:f}}))
            (b/on :b (fn [_] #{:g}))])

          after-all (b/after-all #{:a :b :f}
                                 (fn [_] {:request #{:z}}))
          step (b/step (fn [state evt]
                         (if evt
                           [:started {:block #{:x}
                                      :request #{:q}}]
                           [state {:block #{:y}}])))
          lts (graph/->lts {:plain-map plain-map
                            :bids bids
                            :step step
                            :round-robin round-robin
                            :after-all after-all})]
      (is  lts))))
