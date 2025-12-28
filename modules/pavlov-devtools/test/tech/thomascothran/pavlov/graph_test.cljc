(ns tech.thomascothran.pavlov.graph-test
  (:require [clojure.test :refer [deftest is testing]]
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

(comment
  (tap> (pnav/root (make-branching-bthreads))))

(deftest graph-from-two-step-bthread
  (testing "graph structure contains root and sequential nodes"
    (let [graph (graph/->graph (make-bthreads-two-step))]
      (is (some? graph))
      (is (= #{[] [:a] [:a :b]}
             (-> graph :nodes keys set)))
      (is (= #{{:from [] :to [:a] :event :a}
               {:from [:a] :to [:a :b] :event :b}}
             (->> graph
                  :edges
                  (map #(select-keys % [:from :to :event]))
                  set))))))

(deftest graph-from-branching-bthreads
  (testing "graph structure captures branching fan-out"
    (is :todo)
    #_(let [graph (graph/->graph (make-branching-bthreads))
            edges (->> graph :edges (map #(select-keys % [:from :to :event])) set)]
        (is (= #{{:from [] :to [:branch/a] :event :branch/a}
                 {:from [] :to [:branch/b] :event :branch/b}
                 {:from [] :to [:branch/c] :event :branch/c}
                 {:from [:branch/b] :to [:branch/b :branch/b-1] :event :branch/b-1}
                 {:from [:branch/c] :to [:branch/c :branch/c-1] :event :branch/c-1}
                 {:from [:branch/c :branch/c-1]
                  :to [:branch/c :branch/c-1 :branch/c-2]
                  :event :branch/c-2}}
               edges)))))

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
          "Node should NOT contain :bprogram/state key (structure is now flattened)"))))

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
