(ns tech.thomascothran.pavlov.viz.cytoscape-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.bthread :as b]
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
