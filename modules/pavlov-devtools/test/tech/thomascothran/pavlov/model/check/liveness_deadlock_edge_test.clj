(ns tech.thomascothran.pavlov.model.check.liveness-deadlock-edge-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.model.check.liveness :as liveness]))

(defn path-edge-destination
  [lts path-edges]
  (reduce (fn [node-id {:keys [from to] :as edge}]
            (when (and (= from node-id)
                       (some #(= edge %)
                             (:edges lts)))
              to))
          (:root lts)
          path-edges))

(defn exit-hot-then-deadlock-bthreads
  []
  {:starter (b/bids [{:request #{:setup}}])
   :obligation (b/bids [{:wait-on #{:setup}}
                        {:request #{:cooldown}
                         :hot true}
                        {}])
   :driver (b/bids [{:wait-on #{:setup}}
                    {:request #{:cooldown}}])})

(def converged-hot-deadlock-lts
  {:root :root
   :nodes {:root {:bthread->bid {:chooser {:request #{:a :b}}}}
           :left {:bthread->bid {:step {:request #{:merge-left}}}}
           :right {:bthread->bid {:step {:request #{:merge-right}}}}
           :merged {:bthread->bid {:hotter {:hot true}}}}
   :edges [{:from :root :to :left :event :a}
           {:from :root :to :right :event :b}
           {:from :left :to :merged :event :merge-left}
           {:from :right :to :merged :event :merge-right}]})

(deftest liveness-violation-root-hot-deadlock-uses-empty-path-witness
  (testing "A hot deadlock at the root reports an empty root-to-node witness"
    (let [lts {:root :root
               :nodes {:root {:bthread->bid {:watcher {:hot true}}}}
               :edges []}
          violation (liveness/liveness-violation lts)]
      (is (= :root (:node-id violation)))
      (is (= [] (:path-edges violation)))
      (is (not (contains? violation :cycle-edges)))
      (is (not (contains? violation :path)))
      (is (not (contains? violation :cycle)))
      (is (= (get-in lts [:nodes :root]) (:state violation))))))

(deftest liveness-violation-ignores-deadlock-after-leaving-hot-region
  (testing "A deadlock reached only after exiting the hot region is not a liveness violation"
    (let [lts (graph/->lts (exit-hot-then-deadlock-bthreads))]
      (is (nil? (liveness/liveness-violation lts))))))

(deftest liveness-violation-deadlock-witness-stays-consistent-after-convergence
  (testing "A reported deadlock witness keeps node, state, and path in sync after paths converge"
    (let [violation (liveness/liveness-violation converged-hot-deadlock-lts)]
      (is (= :merged (:node-id violation)))
      (is (= (get-in converged-hot-deadlock-lts [:nodes (:node-id violation)])
             (:state violation)))
      (is (= [{:from :root :to :left :event :a}
              {:from :left :to :merged :event :merge-left}]
             (:path-edges violation)))
      (is (= (:node-id violation)
             (path-edge-destination converged-hot-deadlock-lts (:path-edges violation))))
      (is (contains? (liveness/deadlock-node-ids converged-hot-deadlock-lts)
                     (:node-id violation)))
      (is (liveness/hot? (:state violation)))
      (is (not (contains? violation :cycle-edges)))
      (is (not (contains? violation :path)))
      (is (not (contains? violation :cycle))))))

(deftest liveness-violation-deadlock-witness-does-not-use-empty-path-for-non-root-node
  (testing "A reported deadlock witness must reach its node, and an empty path is only valid at the root"
    (let [lts {:root :root
               :nodes {:root {:bthread->bid {}}
                       :reachable {:bthread->bid {}}
                       :unreachable-hot {:bthread->bid {:watcher {:hot true}}}}
               :edges [{:from :root :to :reachable :event :go}]}
          violation (liveness/liveness-violation lts)]
      (is (or (nil? violation)
              (= (:node-id violation)
                 (path-edge-destination lts (:path-edges violation)))))
      (is (or (nil? violation)
              (not (and (empty? (:path-edges violation))
                         (not= (:root lts) (:node-id violation)))))))))
