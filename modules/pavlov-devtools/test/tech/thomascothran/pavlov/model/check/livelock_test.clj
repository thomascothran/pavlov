(ns tech.thomascothran.pavlov.model.check.livelock-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.model.check.livelock :as livelock]))

(defn- split-merge-lts
  [depth width]
  (let [merge-node-ids (mapv (fn [i] [:merge i])
                             (range (inc depth)))
        branch-node-ids (vec (for [i (range depth)
                                   j (range width)]
                               [:branch i j]))
        edges (vec
               (mapcat (fn [i]
                         (mapcat (fn [j]
                                   [{:from [:merge i]
                                     :to [:branch i j]
                                     :event {:type [:out i j]}}
                                    {:from [:branch i j]
                                     :to [:merge (inc i)]
                                     :event {:type [:in i j]}}])
                                 (range width)))
                       (range depth)))]
    {:root [:merge 0]
     :nodes (into {}
                  (map (fn [node-id] [node-id {}]))
                  (into merge-node-ids branch-node-ids))
     :edges edges
     :truncated false}))

(deftest finds-a-livelock-with-a-rooted-cycle-witness
  (let [lts {:root :root
             :nodes {:root {}
                     :cycle-entry {}
                     :cycle-middle {}
                     :terminal {}}
             :edges [{:from :root
                      :to :terminal
                      :event {:type :done :terminal true}}
                     {:from :root
                      :to :cycle-entry
                      :event {:type :enter-cycle}}
                     {:from :cycle-entry
                      :to :cycle-middle
                      :event {:type :ping}}
                     {:from :cycle-middle
                      :to :cycle-entry
                      :event {:type :pong}}]}
        result (livelock/find-livelocks lts {})]
    (is (= [{:path [:enter-cycle]
             :cycle [:ping :pong]}]
           result))))

(deftest finds-a-self-loop-at-the-root
  (let [lts {:root :root
             :nodes {:root {}}
             :edges [{:from :root
                      :to :root
                      :event {:type :tick}}]}]
    (is (= [{:path []
             :cycle [:tick]}]
           (livelock/find-livelocks lts {})))))

(deftest ignores-cycles-that-can-reach-a-terminal-event
  (let [lts {:root :root
             :nodes {:root {}
                     :loop {}
                     :terminal {}}
             :edges [{:from :root :to :loop :event {:type :enter}}
                     {:from :loop :to :loop :event {:type :tick}}
                     {:from :loop
                      :to :terminal
                      :event {:type :done :terminal true}}]}]
    (is (nil? (livelock/find-livelocks lts {})))))

(deftest honors-disabled-livelock-checking
  (let [lts {:root :root
             :nodes {:root {}}
             :edges [{:from :root
                      :to :root
                      :event {:type :tick}}]}]
    (is (nil? (livelock/find-livelocks
               lts
               {:check-livelock? false})))))

(deftest convergent-graph-is-processed-by-state-rather-than-by-path
  (testing "a 501-node/800-edge DAG does not cause exponential path enumeration"
    (let [lts (split-merge-lts 100 4)
          task (future (livelock/find-livelocks lts {}))]
      (is (= 501 (count (:nodes lts))))
      (is (= 800 (count (:edges lts))))
      (try
        (is (nil? (deref task 5000 ::timeout)))
        (finally
          (future-cancel task))))))
