(ns tech.thomascothran.pavlov.graph.algo-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.graph.algo
             :as algo]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.graph :as g]))

(deftest test-find-path-linear
  (let [bthreads
        {:a (b/bids [{:request #{:a}}])
         :b (b/bids [{:wait-on #{:a}}
                     {:request #{:b}}])
         :c (b/bids [{:wait-on #{:b}}
                     {:request #{:c}}])}
        lts (g/->lts bthreads)

        root-node-id (get lts :root)

        terminal-node-ids (->> (get lts :nodes)
                               (filter (comp empty? :requests second))
                               ffirst
                               (conj #{}))

        outgoing-index (algo/lts->outgoing-index lts)

        path
        (algo/find-path root-node-id
                        (partial algo/succ outgoing-index (constantly true))
                        terminal-node-ids)]

    (is (= [:a :b :c] (mapv :event path)))))

(deftest shortest-path-tree-matches-find-path
  (let [lts {:root 0
             :nodes {0 {} 1 {} 2 {} 3 {} 4 {} 5 {}}
             ;; converging branches, parallel edges, and a cycle to the root
             :edges [{:from 0 :to 1 :event :a}
                     {:from 0 :to 2 :event :b}
                     {:from 2 :to 3 :event :c}
                     {:from 1 :to 3 :event :d}
                     {:from 1 :to 3 :event :e}
                     {:from 3 :to 4 :event :f}
                     {:from 4 :to 0 :event :g}]}
        outgoing-index (algo/lts->outgoing-index lts)
        tree (algo/shortest-path-tree outgoing-index 0)]
    (testing "the first discovering edge in BFS order is the parent"
      (is (= [:a :d :f] (mapv :event (algo/tree-path tree 4)))))
    (testing "the root has an empty path"
      (is (= [] (algo/tree-path tree 0))))
    (testing "unreachable nodes have no path"
      (is (nil? (algo/tree-path tree 5))))
    (testing "every reachable path is the one find-path returns"
      (doseq [node-id (range 5)]
        (is (= (algo/find-path 0
                               (partial algo/succ outgoing-index (constantly true))
                               #{node-id})
               (algo/tree-path tree node-id))
            (str "node " node-id))))))
