(ns tech.thomascothran.pavlov.graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.bthread :as b]))

(defn make-bthreads-two-step
  []
  {:first (b/bids [{:request #{:a}}
                   {:request #{:b}}])})

(defn make-branching-bthreads
  []
  {:chooser (b/bids [{:request #{:branch/a :branch/b :branch/c}}])
   :branch-b (b/on :branch/b (constantly {:request #{:branch/b-1}}))
   :branch-c-advance (b/on :branch/c (constantly {:request #{:branch/c-1}}))
   :branch-c-finish (b/on :branch/c-1 (constantly {:request #{:branch/c-2}}))})

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
    (let [graph (graph/->graph (make-branching-bthreads))
          node-ids (-> graph :nodes keys set)
          edges (->> graph :edges (map #(select-keys % [:from :to :event])) set)]
      (is (= #{[]
               [:branch/a]
               [:branch/b]
               [:branch/b :branch/b-1]
               [:branch/c]
               [:branch/c :branch/c-1]
               [:branch/c :branch/c-1 :branch/c-2]}
             node-ids))
      (is (= #{{:from [] :to [:branch/a] :event :branch/a}
               {:from [] :to [:branch/b] :event :branch/b}
               {:from [] :to [:branch/c] :event :branch/c}
               {:from [:branch/b] :to [:branch/b :branch/b-1] :event :branch/b-1}
               {:from [:branch/c] :to [:branch/c :branch/c-1] :event :branch/c-1}
               {:from [:branch/c :branch/c-1]
                :to [:branch/c :branch/c-1 :branch/c-2]
                :event :branch/c-2}}
             edges)))))
