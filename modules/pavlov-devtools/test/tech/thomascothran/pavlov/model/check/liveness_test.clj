(ns tech.thomascothran.pavlov.model.check.liveness-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.model.check.liveness :as liveness]))

(defn hot-terminal-bthreads
  []
  {:finisher (b/bids [{:request #{{:type :done
                                   :terminal true}}}])
   :watcher (b/bids [{:wait-on #{:done}}
                     {:hot true}])})

(defn hot-deadlock-bthreads
  []
  {:starter (b/bids [{:request #{:setup}}])
   :obligation (b/bids [{:wait-on #{:setup}}
                        {:hot true}])})

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

(defn cold-node-breaks-cycle-bthreads
  []
  (let [looper (b/step
                (fn [state event]
                  (case (or state :waiting)
                    :waiting (if (= :setup event)
                               [:hot {:request #{:ping}
                                      :hot true}]
                               [:waiting {:wait-on #{:setup}}])
                    :hot [:cold {:request #{:pong}}]
                    :cold [:hot {:request #{:ping}
                                 :hot true}])))]
    {:starter (b/bids [{:request #{:setup}}])
     :looper looper}))

(deftest liveness-violation-detects-hot-terminal-path
  (testing "Hot terminal event reports the terminal event in the witness path"
    (let [lts (graph/->lts (hot-terminal-bthreads))
          violation (liveness/liveness-violation lts)]
      (is (= [:done] (:path violation)))
      (is (nil? (:cycle violation)))
      (is (liveness/hot? (:state violation))))))

(deftest liveness-violation-detects-hot-deadlock
  (testing "Hot deadlock reports the path to the stuck hot node"
    (let [lts (graph/->lts (hot-deadlock-bthreads))
          violation (liveness/liveness-violation lts)]
      (is (= [:setup] (:path violation)))
      (is (nil? (:cycle violation)))
      (is (liveness/hot? (:state violation))))))

(deftest liveness-violation-detects-hot-cycle-with-cold-prefix
  (testing "Hot cycle detection returns a cold-prefix path and a hot-only cycle witness"
    (let [lts (graph/->lts (hot-cycle-bthreads))
          violation (liveness/liveness-violation lts)]
      (is (= [:setup] (:path violation)))
      (is (= [:ping :pong] (:cycle violation)))
      (is (liveness/hot? (:state violation))))))

(deftest liveness-violation-ignores-cycles-that-leave-hot-region
  (testing "A cycle that passes through a cold node is not a hot liveness violation"
    (let [lts (graph/->lts (cold-node-breaks-cycle-bthreads))]
      (is (nil? (liveness/liveness-violation lts))))))

(deftest liveness-violation-does-not-flag-terminal-event-that-exits-hot-region
  (testing "A terminal event that leaves the hot region should not be reported as a liveness violation"
    (let [lts (graph/->lts
               {:finisher
                (b/bids [{:request #{:start}}
                         {:request #{{:type :done
                                      :terminal true}}
                          :hot true}])})]
      (is (nil? (liveness/liveness-violation lts))))))

(deftest liveness-violation-flags-hot-terminal-target-node
  (testing "A terminal edge whose target node is hot should be reported as a liveness violation"
    (let [lts (graph/->lts
               {:finisher (b/bids [{:request #{{:type :done
                                                :terminal true}}}])
                :watcher (b/bids [{:wait-on #{:done}}
                                  {:hot true}])})
          violation (liveness/liveness-violation lts)]
      (is (some? violation))
      (is (= [:done] (:path violation)))
      (is (nil? (:cycle violation)))
      (is (liveness/hot? (:state violation))))))

(deftest liveness-violation-handles-non-comparable-event-types
  (testing "Liveness checking should not crash when witness paths contain non-comparable event types"
    (let [event-a {:type {:branch :a}}
          event-b {:type {:branch :b}}
          lts (graph/->lts
               {:starter (b/bids [{:request #{event-a event-b}}])
                :hot-a (b/bids [{:wait-on #{event-a}}
                                {:hot true}])
                :hot-b (b/bids [{:wait-on #{event-b}}
                                {:hot true}])})]
      (is (some? (liveness/liveness-violation lts))))))
