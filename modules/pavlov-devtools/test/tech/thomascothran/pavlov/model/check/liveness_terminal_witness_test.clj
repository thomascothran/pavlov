(ns tech.thomascothran.pavlov.model.check.liveness-terminal-witness-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.model.check.liveness :as liveness]))

(defn- terminal-event
  [event-type]
  {:type event-type
   :terminal true})

(deftest liveness-violation-terminal-witness-uses-violating-edge-when-target-has-another-incoming-path
  (testing "The reported witness should include the terminal event that established the violation"
    (let [lts {:root :r
               :nodes {:r {:bthread->bid {:t {}}
                           :hot false}
                       :y {:bthread->bid {:t {}}
                           :hot false}
                       :x {:bthread->bid {:t {:hot true}}
                           :hot true}}
               :edges [{:from :r :to :x :event :a}
                       {:from :r :to :y :event :b}
                       {:from :y :to :x :event (terminal-event :done)}]}
          violation (liveness/liveness-violation lts)]
      (is (= :x (:node-id violation)))
      (is (= [{:from :r :to :y :event :b}
              {:from :y :to :x :event (terminal-event :done)}]
             (:path-edges violation)))
      (is (not (contains? violation :cycle-edges)))
      (is (not (contains? violation :path)))
      (is (not (contains? violation :cycle)))
      (is (= (get-in lts [:nodes :x]) (:state violation))))))

(deftest liveness-violation-terminal-witness-uses-the-scanned-terminal-edge-when-multiple-terminal-edges-hit-the-same-target
  (testing "The witness should preserve which terminal edge was selected as the violation witness"
    (let [lts {:root :r
               :nodes {:r {:bthread->bid {:t {}}
                           :hot false}
                       :a {:bthread->bid {:t {}}
                           :hot false}
                       :b {:bthread->bid {:t {}}
                           :hot false}
                       :x {:bthread->bid {:t {:hot true}}
                           :hot true}}
               :edges [{:from :r :to :a :event :a}
                       {:from :r :to :b :event :b}
                       {:from :b :to :x :event (terminal-event :done-b)}
                       {:from :a :to :x :event (terminal-event :done-a)}]}
          violation (liveness/liveness-violation lts)]
      (is (= :x (:node-id violation)))
      (is (= [{:from :r :to :b :event :b}
              {:from :b :to :x :event (terminal-event :done-b)}]
             (:path-edges violation)))
      (is (not (contains? violation :cycle-edges)))
      (is (not (contains? violation :path)))
      (is (not (contains? violation :cycle)))
      (is (= (get-in lts [:nodes :x]) (:state violation))))))

(deftest liveness-violation-terminal-witness-characterization-for-generated-lts
  (testing "A generated hot terminal violation still reports a witness ending with the terminal event"
    (let [lts (graph/->lts
               {:finisher (b/bids [{:request #{(terminal-event :done)}}])
                 :watcher (b/bids [{:wait-on #{:done}}
                                   {:hot true}])})
          violation (liveness/liveness-violation lts)]
      (is (some? violation))
      (is (= [(first (:edges lts))]
             (:path-edges violation)))
      (is (= :done
             (-> violation :path-edges last :event :type)))
      (is (not (contains? violation :cycle-edges)))
      (is (not (contains? violation :path)))
      (is (not (contains? violation :cycle)))
      (is (= (get-in lts [:nodes (:node-id violation)])
             (:state violation))))))
