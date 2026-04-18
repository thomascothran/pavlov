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
