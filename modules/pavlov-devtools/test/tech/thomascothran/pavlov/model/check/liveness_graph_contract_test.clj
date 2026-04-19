(ns tech.thomascothran.pavlov.model.check.liveness-graph-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.model.check.liveness :as liveness]))

(defn- same-type-terminal-and-nonterminal-branches-bthreads
  []
  {:chooser (b/bids [{:request #{{:type :done}
                                  {:type :done :terminal true}}}])
   :watcher (b/bids [{:wait-on #{{:type :done}
                                 {:type :done :terminal true}}}
                     {:hot true}])})

(defn- converging-hot-cycle-bthreads
  []
  {:chooser (b/bids [{:request #{:left :right}}])
   :converger (b/bids [{:wait-on #{:left :right}}
                       {:request #{:ping}
                        :hot true}])
   :looper (b/step
            (fn [state event]
              (case (or state :waiting)
                :waiting (if (= :ping event)
                           [:pong {:request #{:pong}
                                   :hot true}]
                           [:waiting {:wait-on #{:ping}}])
                :pong [:ping {:request #{:ping}
                              :hot true}]
                :ping [:pong {:request #{:pong}
                              :hot true}])))}
  )

(defn- trace-edges
  [lts start-node-id witness-edges]
  (reduce (fn [{:keys [node-id visited-node-ids]} edge]
            (is (some #(= edge %) (:edges lts))
                (str "Expected witness edge to be present in the LTS: " edge))
            (is (= node-id (:from edge))
                (str "Expected witness edge to continue from " node-id))
            {:node-id (:to edge)
             :visited-node-ids (conj visited-node-ids (:to edge))})
          {:node-id start-node-id
           :visited-node-ids []}
          witness-edges))

(deftest graph-layer-contract-keeps-terminal-target-distinct-from-nonterminal-peer
  (testing "Terminal and non-terminal routes with the same observable event type remain distinguishable"
    (let [lts (graph/->lts (same-type-terminal-and-nonterminal-branches-bthreads))
           terminal-edge (first (filter #(get-in % [:event :terminal]) (:edges lts)))
           nonterminal-edge (first (remove #(get-in % [:event :terminal]) (:edges lts)))
          violation (liveness/liveness-violation lts)]
      (is (some? terminal-edge))
      (is (some? nonterminal-edge))
      (is (not= (:to terminal-edge) (:to nonterminal-edge))
          "Graph should keep terminal-target identity distinct from its non-terminal peer")
      (is (= (get-in lts [:nodes (:to terminal-edge)])
             (get-in lts [:nodes (:to nonterminal-edge)]))
          "The distinction should survive even when the node payloads otherwise match")
      (is (= (:to terminal-edge) (:node-id violation))
          "Liveness witness should point at the hot terminal target")
      (is (= (get-in lts [:nodes (:node-id violation)])
             (:state violation)))
      (is (= [terminal-edge] (:path-edges violation)))
      (is (not (contains? violation :cycle-edges)))
      (is (not (contains? violation :path)))
      (is (not (contains? violation :cycle))))))

(deftest graph-layer-contract-cycle-witness-stays-coherent-after-convergence
  (testing "A graph->lts witness stays coherent when two paths converge before a hot cycle"
    (let [lts (graph/->lts (converging-hot-cycle-bthreads))
           left-edge (first (filter #(= :left (:event %)) (:edges lts)))
           right-edge (first (filter #(= :right (:event %)) (:edges lts)))
           violation (liveness/liveness-violation lts)]
      (is (= (:to left-edge) (:to right-edge))
          "The two entry branches should merge before the hot strongly connected region")
      (is (= (get-in lts [:nodes (:node-id violation)])
             (:state violation)))
      (is (not (contains? violation :path)))
      (is (not (contains? violation :cycle)))
      (when (and (contains? violation :path-edges)
                 (contains? violation :cycle-edges))
        (let [path-trace (trace-edges lts (:root lts) (:path-edges violation))
              cycle-trace (trace-edges lts (:node-id violation) (:cycle-edges violation))]
          (is (= (:node-id violation) (:node-id path-trace))
              "Witness path should reach the reported cycle entry node")
          (is (= (:node-id violation) (:node-id cycle-trace))
              "Witness cycle should close back on the reported cycle entry node")
          (is (every? #(:hot (get-in lts [:nodes %]))
                      (cons (:node-id violation) (:visited-node-ids cycle-trace)))
              "Witness cycle should stay within the hot region"))))))

(deftest graph-layer-contract-cycle-witness-survives-edge-reordering
  (testing "Reordering graph edges may change witness selection, but should not break cycle correctness"
    (let [lts (graph/->lts (converging-hot-cycle-bthreads))
           reversed-lts (update lts :edges #(vec (reverse %)))]
      (doseq [[label candidate-lts] [[:original lts]
                                     [:reversed reversed-lts]]]
        (let [violation (liveness/liveness-violation candidate-lts)]
          (is (some? (:cycle-edges violation))
              (str label " graph should still report a hot cycle violation"))
          (is (not (contains? violation :path))
              (str label " graph should drop the old :path witness key"))
          (is (not (contains? violation :cycle))
              (str label " graph should drop the old :cycle witness key"))
          (is (= (get-in candidate-lts [:nodes (:node-id violation)])
                 (:state violation))
              (str label " witness state should match the reported node"))
          (when (and (contains? violation :path-edges)
                     (contains? violation :cycle-edges))
            (let [path-trace (trace-edges candidate-lts (:root candidate-lts) (:path-edges violation))
                  cycle-trace (trace-edges candidate-lts (:node-id violation) (:cycle-edges violation))]
              (is (= (:node-id violation) (:node-id path-trace))
                  (str label " path witness should still reach the reported node"))
               (is (= (:node-id violation) (:node-id cycle-trace))
                   (str label " cycle witness should still close after edge reordering"))
               (is (every? #(:hot (get-in candidate-lts [:nodes %]))
                           (cons (:node-id violation) (:visited-node-ids cycle-trace)))
                   (str label " cycle witness should remain entirely hot")))))))))
