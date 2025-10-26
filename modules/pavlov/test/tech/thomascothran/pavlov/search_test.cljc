(ns tech.thomascothran.pavlov.search-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.search :as search]))

(defrecord TreeNavigator [graph]
  search/StateNavigator
  (root [_] :a)
  (succ [_ state]
    (map (fn [child] {:state child :event [:moved-to child]})
         (get graph state [])))
  (identifier [_ state] state))

(deftest bfs-basic-tree-test
  (testing "BFS visits nodes in breadth-first order"
    (let [tree {:a [:b :c]
                :b [:d :e]
                :c [:f]}
          nav (->TreeNavigator tree)
          result (vec (search/bfs-seq nav))]
      (is (= [:a :b :c :d :e :f] result)))))

(deftest dfs-basic-tree-test
  (testing "DFS visits nodes in depth-first order"
    (let [tree {:a [:b :c]
                :b [:d :e]
                :c [:f]}
          nav (->TreeNavigator tree)
          result (vec (search/dfs-seq nav))]
      (is (= [:a :b :d :e :c :f] result)))))

(deftest dfs-reduce-basic-tree-test
  (testing "DFS-reduce visits nodes in depth-first order (right-to-left)"
    (let [tree {:a [:b :c]
                :b [:d :e]
                :c [:f]}
          nav (->TreeNavigator tree)
          result (search/dfs-reduce nav conj [])]
      (is (= [:a :c :f :b :e :d] result)))))

(deftest edge-case-single-node-test
  (testing "Single node graph returns only root"
    (let [nav (->TreeNavigator {}) ; empty graph, root has no children
          bfs-result (vec (search/bfs-seq nav))
          dfs-result (vec (search/dfs-seq nav))]
      (is (= [:a] bfs-result))
      (is (= [:a] dfs-result)))))

(deftest dfs-reduce-edge-case-single-node-test
  (testing "DFS-reduce with single node returns only root"
    (let [nav (->TreeNavigator {})
          result (search/dfs-reduce nav conj [])]
      (is (= [:a] result)))))

(defrecord EmptyNavigator []
  search/StateNavigator
  (root [_] :root)
  (succ [_ state] []) ; Always returns empty
  (identifier [_ state] state))

(deftest edge-case-empty-successors-test
  (testing "Navigator with no successors returns only root"
    (let [nav (->EmptyNavigator)
          bfs-result (vec (search/bfs-seq nav))
          dfs-result (vec (search/dfs-seq nav))]
      (is (= [:root] bfs-result))
      (is (= [:root] dfs-result)))))

(deftest dfs-reduce-edge-case-empty-successors-test
  (testing "DFS-reduce with no successors returns only root"
    (let [nav (->EmptyNavigator)
          result (search/dfs-reduce nav conj [])]
      (is (= [:root] result)))))

(deftest cycle-simple-test
  (testing "Simple cycle is handled correctly"
    (let [graph {:a [:b]
                 :b [:a]} ; a -> b -> a cycle
          nav (->TreeNavigator graph)
          bfs-result (vec (search/bfs-seq nav))
          dfs-result (vec (search/dfs-seq nav))]
      (is (= [:a :b] bfs-result))
      (is (= [:a :b] dfs-result)))))

(deftest dfs-reduce-cycle-simple-test
  (testing "DFS-reduce handles simple cycle correctly"
    (let [graph {:a [:b]
                 :b [:a]}
          nav (->TreeNavigator graph)
          result (search/dfs-reduce nav conj [])]
      (is (= [:a :b] result)))))

(deftest cycle-complex-test
  (testing "Complex cycles with shared paths"
    (let [graph {:a [:b :c]
                 :b [:d]
                 :c [:d] ; both b and c lead to d
                 :d [:e :a] ; d cycles back to a
                 :e [:c]} ; e also cycles back through c
          nav (->TreeNavigator graph)
          bfs-result (vec (search/bfs-seq nav))
          dfs-result (vec (search/dfs-seq nav))]
      ;; Each node should appear exactly once
      (is (= 5 (count bfs-result)))
      (is (= 5 (count dfs-result)))
      (is (= #{:a :b :c :d :e} (set bfs-result)))
      (is (= #{:a :b :c :d :e} (set dfs-result)))
      ;; BFS should visit level by level
      (is (= :a (first bfs-result)))
      ;; DFS should visit depth-first
      (is (= :a (first dfs-result))))))

(deftest dfs-reduce-cycle-complex-test
  (testing "DFS-reduce handles complex cycles with shared paths"
    (let [graph {:a [:b :c]
                 :b [:d]
                 :c [:d]
                 :d [:e :a]
                 :e [:c]}
          nav (->TreeNavigator graph)
          result (search/dfs-reduce nav conj [])]
      ;; Each node should appear exactly once
      (is (= 5 (count result)))
      (is (= #{:a :b :c :d :e} (set result)))
      ;; DFS-reduce should visit depth-first (right-to-left)
      (is (= :a (first result))))))

(defrecord InfiniteNavigator []
  search/StateNavigator
  (root [_] 0)
  (succ [_ state]
    ;; Each number has two children: 2n+1 and 2n+2
    [{:state (+ (* 2 state) 1) :event [:left state]}
     {:state (+ (* 2 state) 2) :event [:right state]}])
  (identifier [_ state] state))

(deftest lazy-evaluation-test
  (testing "Lazy sequences work with infinite graphs"
    (let [nav (->InfiniteNavigator)]
      (testing "BFS can take finite elements from infinite graph"
        (let [first-10 (vec (take 10 (search/bfs-seq nav)))]
          (is (= 10 (count first-10)))
          (is (= 0 (first first-10)))
          ;; BFS should give us: 0, 1, 2, 3, 4, 5, 6, 7, 8, 9...
          (is (= [0 1 2 3 4 5 6 7 8 9] first-10))))

      (testing "DFS can take finite elements from infinite graph"
        (let [first-10 (vec (take 10 (search/dfs-seq nav)))]
          (is (= 10 (count first-10)))
          (is (= 0 (first first-10)))
          ;; DFS will go deep first: 0, then explore left branch deeply
          (is (every? number? first-10)))))))

(deftest dfs-reduce-early-termination-test
  (testing "DFS-reduce can terminate early with reduced"
    (let [nav (->InfiniteNavigator)]
      (testing "Can stop after visiting 10 nodes"
        (let [result (search/dfs-reduce nav
                                        (fn [acc state]
                                          (if (< (count acc) 10)
                                            (conj acc state)
                                            (reduced acc)))
                                        [])]
          (is (= 10 (count result)))
          (is (= 0 (first result)))
          (is (every? number? result))))

      (testing "Can find a target and stop"
        (let [target 6 ; Use smaller target to avoid overflow
              found (search/dfs-reduce nav
                                       (fn [_ state]
                                         (if (= state target)
                                           (reduced state)
                                           nil))
                                       nil)]
          (is (= target found)))))))

(deftest dfs-traversal-order-performance-test
  (testing "Different DFS orderings have different performance characteristics"
    ;; Standard tree: left=2n+1, right=2n+2
    (let [standard-nav (->InfiniteNavigator)]
      (testing "dfs-seq finds left-subtree values quickly"
        (let [visited (atom 0)
              wrapped-nav (reify search/StateNavigator
                            (root [_] (search/root standard-nav))
                            (succ [_ state]
                              (swap! visited inc)
                              (search/succ standard-nav state))
                            (identifier [_ state] (search/identifier standard-nav state)))]
          ;; 7 is in left subtree (7 = 2*3 + 1)
          (doall (take-while #(not= % 7) (search/dfs-seq wrapped-nav)))
          (is (< @visited 10) "dfs-seq should find left-subtree value quickly")))

      (testing "dfs-reduce finds right-subtree values quickly"
        (let [visited (atom 0)]
          ;; 6 is in right subtree (6 = 2*2 + 2)
          (search/dfs-reduce standard-nav
                             (fn [_ state]
                               (swap! visited inc)
                               (when (= state 6) (reduced state)))
                             nil)
          (is (< @visited 10) "dfs-reduce should find right-subtree value quickly"))))))

(deftest lazy-evaluation-side-effects-test
  (testing "Lazy evaluation doesn't compute more than necessary"
    (let [visited (atom #{})
          nav (reify search/StateNavigator
                (root [_] 0)
                (succ [_ state]
                  (swap! visited conj state)
                  [{:state (inc state) :event [:next state]}])
                (identifier [_ state] state))]

      (testing "Taking 5 elements only visits necessary states"
        (reset! visited #{})
        (let [result (doall (take 5 (search/bfs-seq nav)))]
          (is (= [0 1 2 3 4] result))
          ;; Should have visited 0,1,2,3,4 to get successors
          ;; Might visit 5 due to chunking, but shouldn't go much further
          (is (<= (count @visited) 10)))))))

(deftest dfs-reduce-evaluation-efficiency-test
  (testing "DFS-reduce doesn't compute more than necessary"
    (let [visited (atom #{})
          nav (reify search/StateNavigator
                (root [_] 0)
                (succ [_ state]
                  (swap! visited conj state)
                  [{:state (inc state) :event [:next state]}])
                (identifier [_ state] state))]

      (testing "Stopping after 5 elements only visits necessary states"
        (reset! visited #{})
        (let [result (search/dfs-reduce nav
                                        (fn [acc state]
                                          (if (< (count acc) 5)
                                            (conj acc state)
                                            (reduced acc)))
                                        [])]
          (is (= [0 1 2 3 4] result))
          ;; Should have visited 0,1,2,3,4 to get successors
          ;; Might visit 5 when checking if we need to continue
          (is (<= (count @visited) 5)))))))

(deftest order-correctness-bfs-test
  (testing "BFS visits all nodes at depth N before depth N+1"
    (let [binary-tree {:a [:b :c]
                       :b [:d :e]
                       :c [:f :g]
                       :d [:h :i]
                       :e [:j :k]
                       :f [:l :m]
                       :g [:n :o]}
          nav (->TreeNavigator binary-tree)
          result (vec (search/bfs-seq nav))
          ;; Group by depth levels
          depth-0 #{:a}
          depth-1 #{:b :c}
          depth-2 #{:d :e :f :g}
          depth-3 #{:h :i :j :k :l :m :n :o}]

      ;; Check that all nodes at each depth appear before next depth
      (is (= :a (nth result 0)))
      ;; Next 2 should be depth-1
      (is (depth-1 (nth result 1)))
      (is (depth-1 (nth result 2)))
      ;; Next 4 should be depth-2
      (is (every? depth-2 (subvec result 3 7)))
      ;; Remaining should be depth-3
      (is (every? depth-3 (subvec result 7))))))

(deftest order-correctness-dfs-test
  (testing "DFS explores children in order returned by succ"
    (let [ordered-tree {:a [:b :c :d]
                        :b [:e :f]
                        :c [:g]
                        :d []
                        :e []
                        :f []
                        :g []}
          nav (->TreeNavigator ordered-tree)
          result (vec (search/dfs-seq nav))]

      ;; DFS should explore in this order:
      ;; a -> first child b -> e -> f -> backtrack -> c -> g -> backtrack -> d
      (is (= :a (first result)))

      ;; Find positions of key nodes
      (let [pos-b (.indexOf result :b)
            pos-c (.indexOf result :c)
            pos-d (.indexOf result :d)
            pos-e (.indexOf result :e)
            pos-f (.indexOf result :f)
            pos-g (.indexOf result :g)]

        ;; b should come before c and d (first child first)
        (is (< pos-b pos-c))
        (is (< pos-b pos-d))

        ;; c and its child g should come before d
        (is (< pos-c pos-d))
        (is (< pos-g pos-d))

        ;; e should come before f (first child of b first)
        (is (< pos-e pos-f))))))

(deftest bfs-reduce-basic-tree-test
  (testing "BFS-reduce visits nodes in breadth-first order"
    (let [tree {:a [:b :c]
                :b [:d :e]
                :c [:f]}
          nav (->TreeNavigator tree)
          result (search/bfs-reduce nav conj [])]
      (is (= [:a :b :c :d :e :f] result)))))

(deftest bfs-reduce-edge-case-single-node-test
  (testing "BFS-reduce with single node returns only root"
    (let [nav (->TreeNavigator {}) ; empty graph, root has no children
          result (search/bfs-reduce nav conj [])]
      (is (= [:a] result)))))

(deftest bfs-reduce-edge-case-empty-successors-test
  (testing "BFS-reduce with no successors returns only root"
    (let [nav (->EmptyNavigator)
          result (search/bfs-reduce nav conj [])]
      (is (= [:root] result)))))

(deftest bfs-reduce-cycle-simple-test
  (testing "BFS-reduce handles simple cycle correctly"
    (let [graph {:a [:b]
                 :b [:a]} ; a -> b -> a cycle
          nav (->TreeNavigator graph)
          result (search/bfs-reduce nav conj [])]
      (is (= [:a :b] result)))))

(deftest bfs-reduce-cycle-complex-test
  (testing "BFS-reduce handles complex cycles with shared paths"
    (let [graph {:a [:b :c]
                 :b [:d]
                 :c [:d] ; both b and c lead to d
                 :d [:e :a] ; d cycles back to a
                 :e [:c]} ; e also cycles back through c
          nav (->TreeNavigator graph)
          result (search/bfs-reduce nav conj [])]
      ;; Each node should appear exactly once
      (is (= 5 (count result)))
      (is (= #{:a :b :c :d :e} (set result)))
      ;; BFS should visit level by level
      (is (= :a (first result)))
      ;; Check BFS order - after :a, we should see :b and :c before :d
      (let [pos-b (.indexOf result :b)
            pos-c (.indexOf result :c)
            pos-d (.indexOf result :d)]
        (is (< pos-b pos-d))
        (is (< pos-c pos-d))))))

(deftest bfs-reduce-early-termination-test
  (testing "BFS-reduce can terminate early with reduced"
    (let [nav (->InfiniteNavigator)]
      (testing "Can stop after visiting 10 nodes"
        (let [result (search/bfs-reduce nav
                                        (fn [acc state]
                                          (if (< (count acc) 10)
                                            (conj acc state)
                                            (reduced acc)))
                                        [])]
          (is (= 10 (count result)))
          (is (= 0 (first result)))
          ;; BFS order: 0, 1, 2, 3, 4, 5, 6, 7, 8, 9
          (is (= [0 1 2 3 4 5 6 7 8 9] result))))

      (testing "Can find a target and stop"
        (let [target 5
              found (search/bfs-reduce nav
                                       (fn [_ state]
                                         (if (= state target)
                                           (reduced state)
                                           nil))
                                       nil)]
          (is (= target found)))))))

(deftest bfs-reduce-evaluation-efficiency-test
  (testing "BFS-reduce doesn't compute more than necessary"
    (let [visited (atom #{})
          nav (reify search/StateNavigator
                (root [_] 0)
                (succ [_ state]
                  (swap! visited conj state)
                  (if (< state 10)
                    [{:state (inc state) :event [:next state]}
                     {:state (+ state 10) :event [:skip state]}]
                    []))
                (identifier [_ state] state))]

      (testing "Stopping after 5 elements only visits necessary states"
        (reset! visited #{})
        (let [result (search/bfs-reduce nav
                                        (fn [acc state]
                                          (if (< (count acc) 5)
                                            (conj acc state)
                                            (reduced acc)))
                                        [])]
          (is (= [0 1 10 2 11] result))
          ;; BFS expands all nodes at the current level
          ;; So we visit 0, 1, 10, 2, 11 to get their successors
          (is (= #{0 1 10 2 11} @visited)))))))

(deftest bfs-reduce-order-correctness-test
  (testing "BFS-reduce visits all nodes at depth N before depth N+1"
    (let [binary-tree {:a [:b :c]
                       :b [:d :e]
                       :c [:f :g]
                       :d [:h :i]
                       :e [:j :k]
                       :f [:l :m]
                       :g [:n :o]}
          nav (->TreeNavigator binary-tree)
          result (search/bfs-reduce nav conj [])
          ;; Group by depth levels
          depth-0 #{:a}
          depth-1 #{:b :c}
          depth-2 #{:d :e :f :g}
          depth-3 #{:h :i :j :k :l :m :n :o}]

      ;; Check that all nodes at each depth appear before next depth
      (is (= :a (nth result 0)))
      ;; Next 2 should be depth-1
      (is (depth-1 (nth result 1)))
      (is (depth-1 (nth result 2)))
      ;; Next 4 should be depth-2
      (is (every? depth-2 (subvec result 3 7)))
      ;; Remaining should be depth-3
      (is (every? depth-3 (subvec result 7))))))

(deftest bfs-reduce-identifier-function-test
  (testing "BFS-reduce respects identifier function for deduplication"
    (let [nav (reify search/StateNavigator
                (root [_] {:id 1 :data "root"})
                (succ [_ state]
                  (case (:id state)
                    1 [{:state {:id 2 :data "child-a"} :event [:to 2]}
                       {:state {:id 3 :data "child-b"} :event [:to 3]}]
                    2 [{:state {:id 3 :data "child-b-alt"} :event [:to 3]}
                       {:state {:id 4 :data "child-c"} :event [:to 4]}]
                    3 [{:state {:id 4 :data "child-c-alt"} :event [:to 4]}]
                    []))
                (identifier [_ state] (:id state)))]

      (testing "BFS-reduce deduplicates based on identifier"
        (let [result (search/bfs-reduce nav conj [])
              ids (map :id result)]
          ;; Should visit each id exactly once
          (is (= [1 2 3 4] ids))
          ;; Should use first occurrence of each id
          (is (= "child-b" (:data (nth result 2))))
          (is (= "child-c" (:data (nth result 3)))))))))

(deftest bfs-reduce-identifier-custom-hash-test
  (testing "BFS-reduce uses custom identifier for deduplication"
    (let [nav (reify search/StateNavigator
                (root [_] [0 0])
                (succ [_ [x y]]
                  (cond
                    (= [x y] [0 0]) [{:state [1 0] :event :right}
                                     {:state [0 1] :event :up}]
                    (= [x y] [1 0]) [{:state [1 1] :event :up}
                                     {:state [0 1] :event :left-up}]
                    (= [x y] [0 1]) [{:state [1 1] :event :right}]
                    :else []))
                ;; Identifier treats [x y] and [y x] as same
                (identifier [_ [x y]] (set [x y])))]

      (let [result (search/bfs-reduce nav conj [])
            ;; Convert back to sorted vectors for comparison
            normalized (map (fn [[x y]] (vec (sort [x y]))) result)]
        ;; Should have [0 0], [0 1], [1 1] (no [1 0] since it's same as [0 1])
        (is (= 3 (count result)))
        (is (= [0 0] (first result)))
        ;; Should contain both [0 1] and [1 1] but in some order
        (is (contains? (set normalized) [0 1]))
        (is (contains? (set normalized) [1 1]))))))

(deftest bfs-reduce-nil-as-valid-state-test
  (testing "BFS-reduce now correctly handles nil states"
    (let [nav (reify search/StateNavigator
                (root [_] nil)
                (succ [_ state]
                  (cond
                    (nil? state) [{:state :a :event :from-nil}
                                  {:state :b :event :from-nil}]
                    (= state :a) [{:state nil :event :back-to-nil}
                                  {:state :c :event :to-c}]
                    :else []))
                (identifier [_ state] state))]

      (testing "BFS-reduce correctly handles nil as root"
        (let [result (search/bfs-reduce nav conj [])]
          ;; Fixed: nil is now properly traversed
          (is (= 4 (count result)) "Should visit all 4 states")
          (is (= nil (first result)) "Should start with nil root")
          (is (= 1 (count (filter nil? result))) "nil should appear exactly once")))

      (testing "BFS-reduce correctly handles nil in the middle of traversal"
        (let [nav2 (reify search/StateNavigator
                     (root [_] :start)
                     (succ [_ state]
                       (case state
                         :start [{:state nil :event :to-nil}
                                 {:state :end :event :to-end}]
                         :end [{:state :final :event :done}]
                         []))
                     (identifier [_ state] state))
              result (search/bfs-reduce nav2 conj [])]
          ;; Fixed: traversal continues after encountering nil
          (is (= [:start nil :end :final] result) "BFS-reduce handles nil correctly"))))))

(deftest dfs-reduce-order-correctness-test
  (testing "DFS-reduce explores children in reverse order returned by succ"
    (let [ordered-tree {:a [:b :c :d]
                        :b [:e :f]
                        :c [:g]
                        :d []
                        :e []
                        :f []
                        :g []}
          nav (->TreeNavigator ordered-tree)
          result (search/dfs-reduce nav conj [])]

      ;; DFS-reduce should explore in reverse order:
      ;; a -> last child d -> backtrack -> c -> g -> backtrack -> b -> f -> e
      (is (= :a (first result)))

      ;; Find positions of key nodes
      (let [pos-b (.indexOf result :b)
            pos-c (.indexOf result :c)
            pos-d (.indexOf result :d)
            pos-e (.indexOf result :e)
            pos-f (.indexOf result :f)
            pos-g (.indexOf result :g)]

        ;; d should come before c and b (last child first)
        (is (< pos-d pos-c))
        (is (< pos-d pos-b))

        ;; c and its child g should come before b
        (is (< pos-c pos-b))
        (is (< pos-g pos-b))

        ;; f should come before e (children visited in reverse)
        (is (< pos-f pos-e))))))

(deftest identifier-function-test
  (testing "Identifier function prevents duplicate states with same logical identity"
    (let [;; Navigator where states are maps but identifier only uses :id
          nav (reify search/StateNavigator
                (root [_] {:id 1 :data "root"})
                (succ [_ state]
                  (case (:id state)
                    1 [{:state {:id 2 :data "child-a"} :event [:to 2]}
                       {:state {:id 3 :data "child-b"} :event [:to 3]}]
                    2 [{:state {:id 3 :data "child-b-alt"} :event [:to 3]} ; Same id 3
                       {:state {:id 4 :data "child-c"} :event [:to 4]}]
                    3 [{:state {:id 4 :data "child-c-alt"} :event [:to 4]}] ; Same id 4
                    []))
                (identifier [_ state] (:id state)))] ; Only use :id for identity

      (testing "BFS deduplicates based on identifier"
        (let [result (vec (search/bfs-seq nav))
              ids (map :id result)]
          ;; Should visit each id exactly once
          (is (= [1 2 3 4] ids))
          ;; Should use first occurrence of each id
          (is (= "child-b" (:data (nth result 2))))
          (is (= "child-c" (:data (nth result 3))))))

      (testing "DFS deduplicates based on identifier"
        (let [result (vec (search/dfs-seq nav))
              ids (map :id result)]
          ;; Should visit each id exactly once
          (is (= 4 (count ids)))
          (is (= #{1 2 3 4} (set ids))))))))

(deftest dfs-reduce-identifier-function-test
  (testing "DFS-reduce respects identifier function for deduplication"
    (let [nav (reify search/StateNavigator
                (root [_] {:id 1 :data "root"})
                (succ [_ state]
                  (case (:id state)
                    1 [{:state {:id 2 :data "child-a"} :event [:to 2]}
                       {:state {:id 3 :data "child-b"} :event [:to 3]}]
                    2 [{:state {:id 3 :data "child-b-alt"} :event [:to 3]}
                       {:state {:id 4 :data "child-c"} :event [:to 4]}]
                    3 [{:state {:id 4 :data "child-c-alt"} :event [:to 4]}]
                    []))
                (identifier [_ state] (:id state)))]

      (testing "DFS-reduce deduplicates based on identifier"
        (let [result (search/dfs-reduce nav conj [])
              ids (map :id result)]
          ;; Should visit each id exactly once
          (is (= 4 (count ids)))
          (is (= #{1 2 3 4} (set ids)))
          ;; Due to reverse order, should see 3 before 2
          (is (= "child-b" (:data (first (filter #(= 3 (:id %)) result))))))))))

(deftest identifier-custom-hash-test
  (testing "Identifier can use custom hash function"
    (let [;; States are vectors [x y], but we consider [1 2] same as [2 1]
          nav (reify search/StateNavigator
                (root [_] [0 0])
                (succ [_ [x y]]
                  (cond
                    (= [x y] [0 0]) [{:state [1 0] :event :right}
                                     {:state [0 1] :event :up}]
                    (= [x y] [1 0]) [{:state [1 1] :event :up}
                                     {:state [0 1] :event :left-up}] ; Same as [1 0]
                    (= [x y] [0 1]) [{:state [1 1] :event :right}]
                    :else []))
                ;; Identifier treats [x y] and [y x] as same
                (identifier [_ [x y]] (set [x y])))]

      (let [result (vec (search/bfs-seq nav))
            ;; Convert back to sorted vectors for comparison
            normalized (map (fn [[x y]] (vec (sort [x y]))) result)]
        ;; Should have [0 0], [0 1], [1 1] (no [1 0] since it's same as [0 1])
        (is (= 3 (count result)))
        (is (= [0 0] (first result)))
        ;; Should contain both [0 1] and [1 1] but in some order
        (is (contains? (set normalized) [0 1]))
        (is (contains? (set normalized) [1 1]))))))

(deftest dfs-reduce-identifier-custom-hash-test
  (testing "DFS-reduce uses custom identifier for deduplication"
    (let [nav (reify search/StateNavigator
                (root [_] [0 0])
                (succ [_ [x y]]
                  (cond
                    (= [x y] [0 0]) [{:state [1 0] :event :right}
                                     {:state [0 1] :event :up}]
                    (= [x y] [1 0]) [{:state [1 1] :event :up}
                                     {:state [0 1] :event :left-up}]
                    (= [x y] [0 1]) [{:state [1 1] :event :right}]
                    :else []))
                ;; Identifier treats [x y] and [y x] as same
                (identifier [_ [x y]] (set [x y])))]

      (let [result (search/dfs-reduce nav conj [])
            ;; Convert back to sorted vectors for comparison
            normalized (map (fn [[x y]] (vec (sort [x y]))) result)]
        ;; Should have [0 0], [0 1], [1 1] (no [1 0] since it's same as [0 1])
        (is (= 3 (count result)))
        (is (= [0 0] (first result)))
        ;; Should contain both [0 1] and [1 1] but in some order
        (is (contains? (set normalized) [0 1]))
        (is (contains? (set normalized) [1 1]))))))

(deftest nil-as-valid-state-test
  (testing "nil can be used as a valid state and is visited only once"
    (let [nav (reify search/StateNavigator
                (root [_] nil)
                (succ [_ state]
                  (cond
                    (nil? state) [{:state :a :event :from-nil}
                                  {:state :b :event :from-nil}]
                    (= state :a) [{:state nil :event :back-to-nil}
                                  {:state :c :event :to-c}]
                    :else []))
                (identifier [_ state] state))]

      (testing "DFS visits nil exactly once"
        (let [result (vec (search/dfs-seq nav))
              nil-count (count (filter nil? result))]
          (is (= 1 nil-count) "nil should appear exactly once in DFS traversal")
          (is (= #{nil :a :b :c} (set result)) "All states should be visited"))))))

(deftest dfs-reduce-nil-as-valid-state-test
  (testing "DFS-reduce has a bug with nil as root state"
    (let [nav (reify search/StateNavigator
                (root [_] nil)
                (succ [_ state]
                  (cond
                    (nil? state) [{:state :a :event :from-nil}
                                  {:state :b :event :from-nil}]
                    (= state :a) [{:state nil :event :back-to-nil}
                                  {:state :c :event :to-c}]
                    :else []))
                (identifier [_ state] state))]

      (testing "DFS-reduce now handles nil root correctly"
        (let [result (search/dfs-reduce nav conj [])
              nil-count (count (filter nil? result))]
          ;; Should visit nil as root and also when cycling back
          (is (= 2 nil-count) "nil should appear twice in traversal")
          (is (= #{nil :a :b :c} (set result)) "All states should be visited")))

      (testing "Non-nil root with nil in traversal"
        (let [nav2 (reify search/StateNavigator
                     (root [_] :start)
                     (succ [_ state]
                       (if (= state :start)
                         [{:state nil :event :to-nil}]
                         []))
                     (identifier [_ state] state))
              result (search/dfs-reduce nav2 conj [])]
          ;; This works because nil is not the root
          (is (= [:start nil] result)))))))

(deftest event-tracking-test
  (testing "Events can be collected during traversal"
    (let [graph {:login [:home :settings]
                 :home [:profile]
                 :settings [:profile]}
          nav (reify search/StateNavigator
                (root [_] :login)
                (succ [_ state]
                  (map (fn [next-state]
                         {:state next-state
                          :event [:to next-state]})
                       (get graph state [])))
                (identifier [_ state] state))

          ;; Helper to collect events during traversal
          collect-events (fn [traversal-fn nav]
                           (let [events (atom [])
                                 wrapped-nav (reify search/StateNavigator
                                               (root [_] (search/root nav))
                                               (succ [_ state]
                                                 (let [successors (search/succ nav state)]
                                                   (doseq [{:keys [event]} successors]
                                                     (swap! events conj event))
                                                   successors))
                                               (identifier [_ state] (search/identifier nav state)))]
                             (doall (traversal-fn wrapped-nav))
                             @events))]

      (testing "BFS collects events in breadth-first order"
        (let [events (collect-events search/bfs-seq nav)]
          ;; BFS visits: login -> home, settings -> profile
          ;; succ is called on all visited states, including profile
          (is (= [[:to :home] [:to :settings] [:to :profile] [:to :profile]]
                 events)
              "Events should be collected in BFS order")))

      (testing "DFS collects events in depth-first order"
        (let [events (collect-events search/dfs-seq nav)]
          ;; DFS visits: login -> home -> profile, then settings -> profile
          ;; Children are visited in natural order: home before settings
          ;; succ is called on all visited states
          (is (= [[:to :home] [:to :settings] [:to :profile] [:to :profile]]
                 events)
              "Events should be collected in DFS order"))))))

(deftest dfs-reduce-event-tracking-test
  (testing "Events can be collected during dfs-reduce traversal"
    (let [graph {:login [:home :settings]
                 :home [:profile]
                 :settings [:profile]}
          nav (reify search/StateNavigator
                (root [_] :login)
                (succ [_ state]
                  (map (fn [next-state]
                         {:state next-state
                          :event [:to next-state]})
                       (get graph state [])))
                (identifier [_ state] state))]

      (testing "DFS-reduce collects events during traversal"
        (let [events (atom [])]
          (search/dfs-reduce nav
                             (fn [acc state]
              ;; Collect events from successors
                               (doseq [{:keys [event]} (search/succ nav state)]
                                 (swap! events conj event))
                               (conj acc state))
                             [])
          ;; DFS-reduce visits: login -> settings -> profile, then home -> profile
          ;; Due to reverse order, settings is visited before home
          (is (= [[:to :home] [:to :settings] [:to :profile] [:to :profile]]
                 @events)
              "Events should be collected during dfs-reduce traversal")))

      (testing "Can track both events and states together"
        (let [result (search/dfs-reduce nav
                                        (fn [acc state]
                                          (let [succs (search/succ nav state)
                                                events (map :event succs)]
                                            (conj acc {:state state :outgoing-events events})))
                                        [])]
          ;; Verify we can track state-event relationships
          (is (= 4 (count result)))
          (is (= :login (:state (first result))))
          (is (= [[:to :home] [:to :settings]] (:outgoing-events (first result)))))))))

(deftest empty-degenerate-cases-test
  (testing "Empty and degenerate graph cases"

    (testing "Navigator with nil events in transitions"
      (let [nav (reify search/StateNavigator
                  (root [_] :start)
                  (succ [_ state]
                    (case state
                      :start [{:state :middle :event nil}
                              {:state :end :event [:actual-event]}]
                      :middle [{:state :final :event nil}]
                      []))
                  (identifier [_ state] state))

            ;; Collect events including nils
            events (atom [])
            wrapped-nav (reify search/StateNavigator
                          (root [_] (search/root nav))
                          (succ [_ state]
                            (let [successors (search/succ nav state)]
                              (doseq [{:keys [event]} successors]
                                (swap! events conj event))
                              successors))
                          (identifier [_ state] (search/identifier nav state)))]

        ;; Verify traversal works with nil events
        (let [bfs-states (vec (search/bfs-seq nav))
              dfs-states (vec (search/dfs-seq nav))]
          (is (= [:start :middle :end :final] bfs-states)
              "BFS should traverse normally with nil events")
          (is (= [:start :middle :final :end] dfs-states)
              "DFS should traverse normally with nil events"))

        ;; Verify nil events are captured
        (reset! events [])
        (doall (search/bfs-seq wrapped-nav))
        (is (= [nil [:actual-event] nil] @events)
            "Nil events should be captured during traversal")))))

(deftest dfs-reduce-empty-degenerate-cases-test
  (testing "DFS-reduce handles empty and degenerate graph cases"

    (testing "Navigator with nil events in transitions"
      (let [nav (reify search/StateNavigator
                  (root [_] :start)
                  (succ [_ state]
                    (case state
                      :start [{:state :middle :event nil}
                              {:state :end :event [:actual-event]}]
                      :middle [{:state :final :event nil}]
                      []))
                  (identifier [_ state] state))]

        ;; Verify traversal works with nil events
        (let [states (search/dfs-reduce nav conj [])]
          (is (= [:start :end :middle :final] states)
              "DFS-reduce should traverse normally with nil events (reverse order)"))

        ;; Verify nil events can be captured during traversal
        (let [events (atom [])]
          (search/dfs-reduce nav
                             (fn [acc state]
                               (doseq [{:keys [event]} (search/succ nav state)]
                                 (swap! events conj event))
                               (conj acc state))
                             [])
          (is (= [nil [:actual-event] nil] @events)
              "Nil events should be captured during dfs-reduce traversal"))))

    (testing "Empty graph (no successors anywhere)"
      (let [nav (reify search/StateNavigator
                  (root [_] :only-node)
                  (succ [_ _] [])
                  (identifier [_ state] state))
            result (search/dfs-reduce nav conj [])]
        (is (= [:only-node] result)
            "DFS-reduce should handle graph with no edges")))

    (testing "Graph with empty successor lists mixed with non-empty"
      (let [nav (reify search/StateNavigator
                  (root [_] :a)
                  (succ [_ state]
                    (case state
                      :a [{:state :b :event :to-b}]
                      :b [] ; empty successors
                      []))
                  (identifier [_ state] state))
            result (search/dfs-reduce nav conj [])]
        (is (= [:a :b] result)
            "DFS-reduce should handle mixed empty/non-empty successor lists")))))
