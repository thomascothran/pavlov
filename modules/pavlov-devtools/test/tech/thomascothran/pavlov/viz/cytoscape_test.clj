(ns tech.thomascothran.pavlov.viz.cytoscape-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.viz.cytoscape :as cytoscape]))

(defn make-bthreads-linear
  []
  {:single (b/bids [{:request #{:step/a}}
                    {:request #{:step/b}}])})

(deftest graph-helper-builds-cytoscape-structure
  (testing "helper converts graph nodes and edges into cytoscape data"
    (let [graph {:nodes {[] {:path []
                             :identifier :root
                             :event nil
                             :wrapped {:path []
                                       :saved-bthread-states {:single :s0}
                                       :bprogram/state {:last-event nil
                                                        :next-event nil
                                                        :requests {}
                                                        :waits {}
                                                        :blocks {}
                                                        :bthread->bid {:single nil}
                                                        :bthreads-by-priority #{:single}}}}
                         [:a] {:path [:a]
                               :identifier :foo
                               :event :a
                               :wrapped {:path [:a]
                                         :saved-bthread-states {:single :s1}
                                         :bprogram/state {:last-event :a
                                                          :next-event :done
                                                          :requests {:foo #{:single}}
                                                          :waits {:foo #{:single}}
                                                          :blocks {:foo #{:single}}
                                                          :bthread->bid {:single {:request #{:foo}}}
                                                          :bthreads-by-priority #{:single}}}}}
                 :edges [{:from []
                          :to [:a]
                          :event :a}]}
          path->id @#'cytoscape/path->id
          result (cytoscape/-graph->cytoscape graph)]
      (is (= {:nodes [{:data {:id (path->id [])
                              :label "initialize"
                              :path []
                              :identifier :root
                              :event nil
                              :meta {:path []
                                     :identifier :root
                                     :event nil
                                     :saved-bthread-states {:single :s0}
                                     :bprogram/state {:last-event nil
                                                      :next-event nil
                                                      :requests {}
                                                      :waits {}
                                                      :blocks {}
                                                      :bthread->bid {:single nil}
                                                      :bthreads-by-priority #{:single}}}
                              :flags {:environment? false
                                      :invariant? false
                                      :terminal? false}}}
                      {:data {:id (path->id [:a])
                              :label ":a"
                              :path [:a]
                              :identifier :foo
                              :event :a
                              :meta {:path [:a]
                                     :identifier :foo
                                     :event :a
                                     :saved-bthread-states {:single :s1}
                                     :bprogram/state {:last-event :a
                                                      :next-event :done
                                                      :requests {:foo #{:single}}
                                                      :waits {:foo #{:single}}
                                                      :blocks {:foo #{:single}}
                                                      :bthread->bid {:single {:request #{:foo}}}
                                                      :bthreads-by-priority #{:single}}}
                              :flags {:environment? false
                                      :invariant? false
                                      :terminal? false}}}]
              :edges [{:data {:id (str (path->id []) "->" (path->id [:a]))
                              :source (path->id [])
                              :target (path->id [:a])
                              :event :a
                              :from []
                              :to [:a]}}]}
             result)))))

(deftest graph->cytoscape-builds-from-bthreads
  (testing "graph->cytoscape integrates graph generation and conversion"
    (let [expected-bthreads (make-bthreads-linear)
          result-bthreads (make-bthreads-linear)
          expected (cytoscape/-graph->cytoscape (graph/->graph expected-bthreads))
          result (cytoscape/graph->cytoscape result-bthreads)]
      (is (= expected result)))))

(deftest lts->cytoscape-basic-structure
  (testing "converts simple LTS to cytoscape format with correct structure"
    (let [;; Create real bthreads: one requests :a then :b
          bthreads {:simple (b/bids [{:request #{:a}}
                                     {:request #{:b}}])}
          ;; Generate real LTS
          lts (graph/->lts bthreads {:max-nodes 10})
          result (cytoscape/lts->cytoscape lts)]

      (testing "result has expected keys"
        (is (contains? result :nodes))
        (is (contains? result :edges)))

      (testing "result has correct counts"
        ;; We should have 3 nodes: initial, after :a, after :b
        (is (= 3 (count (:nodes result))) "should have 3 nodes")
        ;; We should have 2 edges: one for :a, one for :b
        (is (= 2 (count (:edges result))) "should have 2 edges")))))

(deftest lts->cytoscape-node-data-structure
  (testing "each node has correct data structure with id, label, and meta"
    (let [;; Create real bthreads with named events
          bthreads {:bt1 (b/bids [{:request #{:e1}}
                                  {:request #{:e2}}])}
          ;; Generate real LTS
          lts (graph/->lts bthreads {:max-nodes 10})
          result (cytoscape/lts->cytoscape lts)
          nodes (:nodes result)]

      (testing "each node has :data map"
        (is (every? #(contains? % :data) nodes)
            "all nodes should have :data key"))

      (testing "node :data contains required keys"
        (doseq [node nodes]
          (is (contains? (:data node) :id) "node should have :id")
          (is (contains? (:data node) :label) "node should have :label")
          (is (contains? (:data node) :meta) "node should have :meta")))

      (testing ":id is a stringified identifier"
        (let [node-ids (set (map #(get-in % [:data :id]) nodes))]
          (is (every? string? node-ids) "all :id values should be strings")
          ;; IDs should be pr-str of state identifiers (vectors)
          (is (every? #(re-find #"^\[" %) node-ids) "IDs should start with [ (stringified vectors)")))

      (testing ":label is an empty string"
        (is (every? #(= "" (get-in % [:data :label])) nodes)
            "all :label values should be empty strings"))

      (testing ":meta contains original LTS node data"
        (doseq [node nodes]
          (let [node-id-str (get-in node [:data :id])
                ;; Parse the stringified node-id back to get the original LTS node-id
                lts-node-id (read-string node-id-str)
                lts-node-data (get-in lts [:nodes lts-node-id])
                meta-data (get-in node [:data :meta])]
            (is (some? lts-node-data) (str "LTS should have data for node " node-id-str))
            (is (= lts-node-data meta-data)
                (str "node " node-id-str " :meta should contain original LTS data"))
            ;; Verify meta has expected keys from real LTS
            (is (contains? meta-data :bthread->bid) "meta should have :bthread->bid")
            (is (contains? meta-data :requests) "meta should have :requests")
            (is (contains? meta-data :waits) "meta should have :waits")
            (is (contains? meta-data :blocks) "meta should have :blocks")))))))

(deftest lts->cytoscape-edge-data-structure
  (testing "each edge has correct data structure"
    (let [;; Create bthreads that produce multiple edges
          bthreads {:simple (b/bids [{:request #{:my-event}}
                                     {:request #{:another-event}}])}
          ;; Generate real LTS
          lts (graph/->lts bthreads {:max-nodes 10})
          result (cytoscape/lts->cytoscape lts)
          edges (:edges result)
          nodes (:nodes result)
          node-ids (set (map #(get-in % [:data :id]) nodes))]

      (testing "each edge has :data map"
        (is (every? #(contains? % :data) edges)
            "all edges should have :data key"))

      (testing "edge :data contains required keys"
        (doseq [edge edges]
          (is (contains? (:data edge) :id) "edge should have :id")
          (is (contains? (:data edge) :source) "edge should have :source")
          (is (contains? (:data edge) :target) "edge should have :target")
          (is (contains? (:data edge) :label) "edge should have :label")
          (is (contains? (:data edge) :event) "edge should have :event")))

      (testing ":id is a unique string identifier"
        (let [edge-ids (map #(get-in % [:data :id]) edges)]
          (is (every? string? edge-ids) "all :id values should be strings")
          (is (= (count edge-ids) (count (set edge-ids))) "all :id values should be unique")))

      (testing ":source and :target reference valid node IDs"
        (doseq [edge edges]
          (let [source (get-in edge [:data :source])
                target (get-in edge [:data :target])]
            (is (string? source) ":source should be a string")
            (is (string? target) ":target should be a string")
            (is (contains? node-ids source)
                (str ":source " source " should reference a valid node ID"))
            (is (contains? node-ids target)
                (str ":target " target " should reference a valid node ID")))))

      (testing ":label is the stringified event type"
        ;; Real events are keywords, so label should be their string representation
        (let [first-edge (first edges)
              second-edge (second edges)]
          (is (= ":my-event" (get-in first-edge [:data :label]))
              "first edge label should be stringified event type")
          (is (= ":another-event" (get-in second-edge [:data :label]))
              "second edge label should be stringified event type")))

      (testing ":event contains the full event data"
        ;; Real events are keywords (not maps)
        (let [first-edge (first edges)
              second-edge (second edges)]
          (is (= :my-event (get-in first-edge [:data :event]))
              "first edge should contain the keyword event")
          (is (= :another-event (get-in second-edge [:data :event]))
              "second edge should contain the keyword event"))))))

(deftest lts->cytoscape-root-node-class
  (testing "root node is marked with 'root' CSS class"
    (let [;; Create simple bthreads
          bthreads {:simple (b/bids [{:request #{:e1}}
                                     {:request #{:e2}}])}
          ;; Generate real LTS
          lts (graph/->lts bthreads {:max-nodes 10})
          result (cytoscape/lts->cytoscape lts)
          nodes (:nodes result)
          root-id-str (pr-str (:root lts))
          root-node (first (filter #(= root-id-str (get-in % [:data :id])) nodes))
          non-root-nodes (filter #(not= root-id-str (get-in % [:data :id])) nodes)]

      (testing "root node has :classes 'root'"
        (is (some? root-node) "root node should exist")
        (is (= "root" (:classes root-node))
            "root node should have :classes 'root' at top level (not inside :data)"))

      (testing "non-root nodes do NOT have :classes 'root'"
        (is (seq non-root-nodes) "there should be non-root nodes to test")
        (doseq [node non-root-nodes]
          (is (not= "root" (:classes node))
              (str "node " (get-in node [:data :id]) " should not have :classes 'root'")))))))

(deftest lts->cytoscape-terminal-node-class
  (testing "terminal nodes are marked with 'terminal' CSS class"
    (let [;; Create bthreads where last event has :terminal true
          bthreads {:simple (b/bids [{:request #{:e1}}
                                     {:request #{{:type :e2 :terminal true}}}])}
          ;; Generate real LTS
          lts (graph/->lts bthreads {:max-nodes 10})
          result (cytoscape/lts->cytoscape lts)
          nodes (:nodes result)
          edges (:edges lts)
          ;; Find the node that has the terminal event leading to it
          terminal-edge (first (filter #(e/terminal? (:event %)) edges))
          terminal-node-id-str (pr-str (:to terminal-edge))
          terminal-node (first (filter #(= terminal-node-id-str (get-in % [:data :id])) nodes))
          non-terminal-nodes (filter #(not= terminal-node-id-str (get-in % [:data :id])) nodes)]

      (testing "terminal node has :classes 'terminal'"
        (is (some? terminal-node) "terminal node should exist")
        (is (= "terminal" (:classes terminal-node))
            "terminal node should have :classes 'terminal' at top level (not inside :data)"))

      (testing "non-terminal nodes do NOT have :classes 'terminal'"
        (is (seq non-terminal-nodes) "there should be non-terminal nodes to test")
        (doseq [node non-terminal-nodes]
          (is (not= "terminal" (:classes node))
              (str "node " (get-in node [:data :id]) " should not have :classes 'terminal'")))))))

(deftest lts->cytoscape-deadlock-node-class
  (testing "deadlock nodes are marked with 'deadlock' CSS class"
    (let [;; Create bthreads where execution ends without terminal event
          ;; This creates a deadlock: bthread waits for :e2 but no one requests it
          bthreads {:requester (b/bids [{:request #{:e1}}])
                    :blocker (b/bids [{:wait-on #{:e1}}
                                      {:block #{:e2}}])}
          ;; Generate real LTS
          lts (graph/->lts bthreads {:max-nodes 10})
          result (cytoscape/lts->cytoscape lts)
          nodes (:nodes result)
          edges (:edges lts)
          ;; Find leaf nodes (nodes with no outgoing edges)
          nodes-with-outgoing (into #{} (map :from) edges)
          terminal-nodes (into #{} (comp (filter #(e/terminal? (:event %)))
                                         (map :to)) edges)
          root (:root lts)
          ;; Deadlock node: has incoming edge, no outgoing edges, not terminal, not root
          deadlock-node-id (first (filter (fn [node-id]
                                            (and (not (contains? nodes-with-outgoing node-id))
                                                 (not (contains? terminal-nodes node-id))
                                                 (not= node-id root)
                                                 (some #(= node-id (:to %)) edges)))
                                          (keys (:nodes lts))))
          deadlock-node-id-str (pr-str deadlock-node-id)
          deadlock-node (first (filter #(= deadlock-node-id-str (get-in % [:data :id])) nodes))
          non-deadlock-nodes (filter #(not= deadlock-node-id-str (get-in % [:data :id])) nodes)]

      (testing "deadlock node has :classes 'deadlock'"
        (is (some? deadlock-node) "deadlock node should exist")
        (is (= "deadlock" (:classes deadlock-node))
            "deadlock node (leaf with non-terminal incoming edge) should have :classes 'deadlock'"))

      (testing "non-deadlock nodes do NOT have :classes 'deadlock'"
        (is (seq non-deadlock-nodes) "there should be non-deadlock nodes to test")
        (doseq [node non-deadlock-nodes]
          (is (not= "deadlock" (:classes node))
              (str "node " (get-in node [:data :id]) " should not have :classes 'deadlock'")))))))

(deftest lts->cytoscape-integration-with-bthreads
  (testing "end-to-end integration: bthreads -> LTS -> cytoscape format"
    (let [;; Create simple bthreads - one requests :a then :b, another requests :c
          bthreads {:linear (b/bids [{:request #{:step/a}}
                                     {:request #{:step/b}}])
                    :single (b/bids [{:request #{:step/c}}])}
          ;; Generate LTS from bthreads with higher limit to avoid truncation
          lts (graph/->lts bthreads {:max-nodes 1000})
          ;; Convert to Cytoscape format
          result (cytoscape/lts->cytoscape lts)]

      (testing "result has expected top-level structure"
        (is (map? result) "result should be a map")
        (is (contains? result :nodes) "result should have :nodes key")
        (is (contains? result :edges) "result should have :edges key")
        (is (vector? (:nodes result)) "nodes should be a vector")
        (is (vector? (:edges result)) "edges should be a vector"))

      (testing "at least one node exists"
        (is (seq (:nodes result)) "should have at least one node"))

      (testing "root node has 'root' class"
        (let [root-id (pr-str (:root lts))
              root-node (first (filter #(= root-id (get-in % [:data :id])) (:nodes result)))]
          (is (some? root-node) "root node should exist in cytoscape data")
          (is (= "root" (:classes root-node)) "root node should have :classes 'root'")))

      (testing "all nodes have correct structure"
        (doseq [node (:nodes result)]
          (is (contains? node :data) "node should have :data key")
          (is (contains? (:data node) :id) "node :data should have :id")
          (is (string? (get-in node [:data :id])) ":id should be a string")
          (is (contains? (:data node) :label) "node :data should have :label")
          (is (string? (get-in node [:data :label])) ":label should be a string")
          (is (contains? (:data node) :meta) "node :data should have :meta")))

      (testing "all edges have correct structure"
        (let [node-ids (set (map #(get-in % [:data :id]) (:nodes result)))]
          (doseq [edge (:edges result)]
            (is (contains? edge :data) "edge should have :data key")
            (is (contains? (:data edge) :id) "edge :data should have :id")
            (is (string? (get-in edge [:data :id])) "edge :id should be a string")
            (is (contains? (:data edge) :source) "edge :data should have :source")
            (is (contains? (:data edge) :target) "edge :data should have :target")
            (is (string? (get-in edge [:data :source])) "edge :source should be a string")
            (is (string? (get-in edge [:data :target])) "edge :target should be a string")
            (is (contains? (:data edge) :label) "edge :data should have :label")
            (is (string? (get-in edge [:data :label])) "edge :label should be a string")
            (is (contains? (:data edge) :event) "edge :data should have :event"))))

      (testing "LTS node data is preserved in cytoscape node :meta"
        (doseq [node (:nodes result)]
          (let [node-id-str (get-in node [:data :id])
                ;; Parse the stringified node-id back to get the original LTS node-id
                ;; (e.g., "[nil {...} {...}]" is pr-str of the LTS identifier)
                lts-node-id (read-string node-id-str)
                lts-node-data (get-in lts [:nodes lts-node-id])]
            (is (some? lts-node-data) (str "LTS should have data for node " node-id-str))
            (is (= lts-node-data (get-in node [:data :meta]))
                (str "node " node-id-str " :meta should contain original LTS data"))))))))
