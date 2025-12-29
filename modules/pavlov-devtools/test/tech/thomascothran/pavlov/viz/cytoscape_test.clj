(ns tech.thomascothran.pavlov.viz.cytoscape-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.viz.cytoscape :as cytoscape]))

;; Access the private path->id function for testing
(def path->id @#'cytoscape/path->id)

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

      (testing ":id uses path->id format (node-XXXXXXXX)"
        (let [node-ids (set (map #(get-in % [:data :id]) nodes))]
          (is (every? string? node-ids) "all :id values should be strings")
          ;; IDs should match the path->id format: "node-" followed by 8 hex digits
          (is (every? #(re-matches #"node-[0-9a-f]{8}" %) node-ids)
              "IDs should match path->id format: node-XXXXXXXX")))

      (testing ":label is an empty string"
        (is (every? #(= "" (get-in % [:data :label])) nodes)
            "all :label values should be empty strings"))

      (testing ":meta contains original LTS node data"
        (doseq [node nodes]
          (let [meta-data (get-in node [:data :meta])]
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
          ;; Use path->id to generate the expected root ID
          root-id-str (path->id (:root lts))
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
          ;; Use path->id to generate the expected terminal node ID
          terminal-node-id-str (path->id (:to terminal-edge))
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
          ;; Use path->id to generate the expected deadlock node ID
          deadlock-node-id-str (path->id deadlock-node-id)
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
        ;; Use path->id to generate the expected root ID
        (let [root-id (path->id (:root lts))
              root-node (first (filter #(= root-id (get-in % [:data :id])) (:nodes result)))]
          (is (some? root-node) "root node should exist in cytoscape data")
          (is (= "root" (:classes root-node)) "root node should have :classes 'root'")))

      (testing "all nodes have correct structure"
        (doseq [node (:nodes result)]
          (is (contains? node :data) "node should have :data key")
          (is (contains? (:data node) :id) "node :data should have :id")
          (is (string? (get-in node [:data :id])) ":id should be a string")
          ;; Verify ID matches path->id format
          (is (re-matches #"node-[0-9a-f]{8}" (get-in node [:data :id]))
              ":id should match path->id format: node-XXXXXXXX")
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
          (let [meta-data (get-in node [:data :meta])]
            ;; We can no longer reverse the hash to get the original LTS node-id
            ;; Instead, verify that :meta contains expected LTS structure
            (is (some? meta-data) "node should have :meta")
            (is (contains? meta-data :bthread->bid) ":meta should have :bthread->bid")
            (is (contains? meta-data :requests) ":meta should have :requests")
            (is (contains? meta-data :waits) ":meta should have :waits")
            (is (contains? meta-data :blocks) ":meta should have :blocks")))))))
