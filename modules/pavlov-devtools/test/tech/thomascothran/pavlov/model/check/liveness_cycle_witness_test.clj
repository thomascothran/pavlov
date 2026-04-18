(ns tech.thomascothran.pavlov.model.check.liveness-cycle-witness-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.model.check.liveness :as liveness]))

(defn- hot-node
  []
  {:bthread->bid {:watcher {:hot true}}})

(defn- cold-node
  []
  {:bthread->bid {:watcher {}}})

(defn- outgoing-edges
  [lts node-id event-type]
  (into []
        (filter (fn [{:keys [from event]}]
                  (and (= node-id from)
                       (= event-type (e/type event)))))
        (:edges lts)))

(defn- trace-edges
  [lts start-node-id witness-edges]
  (reduce (fn [{:keys [node-id edges nodes]} edge]
            (is (some #(= edge %) (:edges lts))
                (str "Expected witness edge to be present in the LTS: " edge))
            (is (= node-id (:from edge))
                (str "Expected witness edge to continue from " node-id))
            {:node-id (:to edge)
             :edges (conj edges edge)
             :nodes (conj nodes (:to edge))})
          {:node-id start-node-id
           :edges []
           :nodes [start-node-id]}
          witness-edges))

(defn hot-cycle-bthreads
  []
  (let [looper (b/step
                (fn [state event]
                  (case (or state :waiting)
                    :waiting (if (= :setup event)
                               [:ping {:request #{:ping}
                                       :hot true}]
                               [:waiting {:wait-on #{:setup}}])
                    :ping [:pong {:request #{:pong}
                                  :hot true}]
                    :pong [:ping {:request #{:ping}
                                  :hot true}])))]
    {:starter (b/bids [{:request #{:setup}}])
     :looper looper}))

(deftest liveness-violation-cycle-witness-returns-to-reported-node
  (testing "A reported cycle witness closes back on the reported node"
    (let [lts {:root :hot
               :nodes {:hot (hot-node)}
               :edges [{:from :hot
                        :to :hot
                        :event {:type :tick}}]}
          violation (liveness/liveness-violation lts)
          expected-cycle-edges [{:from :hot
                                 :to :hot
                                 :event {:type :tick}}]]
      (is (= :hot (:node-id violation)))
      (is (= [] (:path-edges violation)))
      (is (= expected-cycle-edges (:cycle-edges violation)))
      (is (not (contains? violation :path)))
      (is (not (contains? violation :cycle)))
      (when-let [cycle-edges (:cycle-edges violation)]
        (let [cycle-trace (trace-edges lts (:node-id violation) cycle-edges)]
          (is (= (:node-id violation) (:node-id cycle-trace)))))
      (is (= (get-in lts [:nodes (:node-id violation)]) (:state violation))))))

(deftest liveness-violation-cycle-witness-stays-within-hot-region
  (testing "Every node traversed by a reported cycle witness stays hot"
    (let [lts {:root :root
               :nodes {:root (cold-node)
                       :entry (hot-node)
                       :middle (hot-node)
                       :cold-detour (cold-node)}
               :edges [{:from :root :to :entry :event {:type :enter}}
                       {:from :entry :to :cold-detour :event {:type :detour}}
                       {:from :entry :to :middle :event {:type :cycle-a}}
                       {:from :middle :to :entry :event {:type :cycle-b}}]}
          violation (liveness/liveness-violation lts)
          expected-path-edges [{:from :root :to :entry :event {:type :enter}}]
          expected-cycle-edges [{:from :entry :to :middle :event {:type :cycle-a}}
                                {:from :middle :to :entry :event {:type :cycle-b}}]]
      (is (= :entry (:node-id violation)))
      (is (= expected-path-edges (:path-edges violation)))
      (is (= expected-cycle-edges (:cycle-edges violation)))
      (is (not (contains? violation :path)))
      (is (not (contains? violation :cycle)))
      (when-let [cycle-edges (:cycle-edges violation)]
        (let [cycle-trace (trace-edges lts (:node-id violation) cycle-edges)]
          (is (= (:node-id violation) (:node-id cycle-trace)))
          (is (every? (fn [node-id]
                        (liveness/hot? (get-in lts [:nodes node-id])))
                      (:nodes cycle-trace))))))))

(deftest liveness-violation-path-and-cycle-compose-into-a-coherent-witness
  (testing "The root path and cycle witness compose into one coherent execution"
    (let [lts (graph/->lts (hot-cycle-bthreads))
          violation (liveness/liveness-violation lts)]
      (is (some? (:cycle-edges violation)))
      (is (not (contains? violation :path)))
      (is (not (contains? violation :cycle)))
      (is (= (get-in lts [:nodes (:node-id violation)]) (:state violation)))
      (when (and (contains? violation :path-edges)
                 (contains? violation :cycle-edges))
        (let [path-trace (trace-edges lts (:root lts) (:path-edges violation))
              cycle-trace (trace-edges lts (:node-id path-trace) (:cycle-edges violation))]
          (is (= (:node-id violation) (:node-id path-trace)))
          (is (= (:node-id violation) (:node-id cycle-trace)))
          (is (every? (fn [node-id]
                        (liveness/hot? (get-in lts [:nodes node-id])))
                      (rest (:nodes cycle-trace)))))))))
