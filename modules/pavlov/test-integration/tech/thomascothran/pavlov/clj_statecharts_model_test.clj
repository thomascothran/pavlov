(ns tech.thomascothran.pavlov.clj-statecharts-model-test
  (:require [clojure.test :refer [deftest is]]
            [statecharts.core :as fsm]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.clj-statecharts :as sc]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.model.check :as check]))

(def branch-chart
  {:id :branches :initial :idle :context {:choice nil}
   :states
   {:idle {:on {:choose {:target :working
                         :actions (fsm/assign
                                   (fn [s e] (assoc s :choice (:choice e))))}}}
    :working
    {::sc/hot true
     :initial :ready
     :on {:left-done {:target :done :guard (fn [s _] (= :left (:choice s)))}
          :right-done {:target :done :guard (fn [s _] (= :right (:choice s)))}}
     :states
     {:ready {::sc/bid (fn [s]
                        {:request #{{:type (case (:choice s)
                                             :left :left-done
                                             :right :right-done)
                                     :choice (:choice s)
                                     :terminal true}}})}}}
    :done {}}})

(defn branch-bthreads []
  {:chart (sc/bthread branch-chart)
   :environment (b/bids [{:request #{{:type :choose :choice :left}
                                    {:type :choose :choice :right}}}])})

(defn choice-safety []
  (b/step
   (fn [choice e]
     (cond
       (= :choose (event/type e))
       [(:choice e) {:wait-on #{:left-done :right-done}}]

       (contains? #{:left-done :right-done} (event/type e))
       [choice (when-not (and (= choice (:choice e))
                             (= (event/type e) ({:left :left-done :right :right-done} choice)))
                 {:request #{{:type :wrong-branch :invariant-violated true}}})]

       :else [choice {:wait-on #{:choose}}]))))

(deftest explores-both-contexts-without-cross-branch-state-leaks
  (is (nil? (check/check {:bthreads (branch-bthreads)
                          :safety-bthreads {:choice (choice-safety)}
                          :possible #{:left-done :right-done}
                          :max-nodes 30})))
  (let [lts (graph/->lts (branch-bthreads) {:max-nodes 30})
        working-states (->> (:nodes lts) vals
                            (map #(get-in % [:saved-bthread-states :chart]))
                            (filter #(= [:working :ready] (:_state %))))]
    (is (false? (:truncated lts)))
    (is (= #{:left :right} (set (map :choice working-states)))
        "Identical control states with different context must remain distinct")))

(defn parallel-bthreads []
  {:chart
   (sc/bthread
    {:id :parallel :initial :work
     :on {:done :finished}
     :states
     {:work {::sc/hot true :type :parallel
             :regions
             {:left {:initial :waiting
                     :states {:waiting {::sc/bid {:request #{:left-step}}
                                        :on {:left-step :done}}
                              :done {}}}
              :right {:initial :waiting
                      :states {:waiting {::sc/bid {:request #{:right-step}}
                                         :on {:right-step :done}}
                               :done {}}}}}
      :finished {}}})
   :finish (b/after-all #{:left-step :right-step}
                        (fn [_] {:request #{{:type :done :terminal true}}}))})

(deftest explores-parallel-interleavings-and-discharges-parent-hot-state
  (is (nil? (check/check {:bthreads (parallel-bthreads)
                          :possible #{:done}
                          :max-nodes 40})))
  (let [lts (graph/->lts (parallel-bthreads) {:max-nodes 40})
        states (into #{} (map #(get-in % [:saved-bthread-states :chart :_state]))
                     (vals (:nodes lts)))]
    (is (false? (:truncated lts)))
    (is (contains? states {:work {:left :done :right :waiting}}))
    (is (contains? states {:work {:left :waiting :right :done}}))
    (is (contains? states :finished))))

(deftest reports-hot-deadlock
  (let [result (check/check
                {:bthreads {:chart (sc/bthread
                                    {:id :stuck :initial :waiting
                                     :states {:waiting {::sc/hot true
                                                        :on {:release :done}}
                                              :done {}}})}
                 :max-nodes 10})]
    (is (seq (:deadlocks result)))
    (is (map? (:liveness-violation result)))
    (is (not (:truncated result)))))

(defn cycle-chart [hot?]
  {:id :cycle :initial :loop
   :states {:loop {::sc/hot hot? :initial :ping
                    :states {:ping {::sc/bid {:request #{:ping}}
                                    :on {:ping :pong}}
                             :pong {::sc/bid {:request #{:pong}}
                                    :on {:pong :ping}}}}}})

(deftest hot-cycle-is-detected-and-cold-cycle-is-not-a-liveness-violation
  (let [hot-result (check/check {:bthreads {:chart (sc/bthread (cycle-chart true))}
                                 :check-livelock? false :max-nodes 20})
        cold-result (check/check {:bthreads {:chart (sc/bthread (cycle-chart false))}
                                  :check-livelock? false :max-nodes 20})]
    (is (seq (get-in hot-result [:liveness-violation :cycle-edges])))
    (is (not (:truncated hot-result)) "Restored snapshots must close the cycle")
    (is (nil? cold-result))))

(deftest reports-forbidden-state-on-only-one-branch
  (let [result (check/check
                {:bthreads
                 {:chart (sc/bthread
                          {:id :safety :initial :start
                           :states {:start {::sc/bid {:request #{:safe :unsafe}}
                                            :on {:safe :good :unsafe :bad}}
                                    :good {::sc/bid {:request #{{:type :done :terminal true}}}}
                                    :bad {::sc/invariant-violated true}}})}
                 :possible #{:done}
                 :max-nodes 20})
        violation (first (:safety-violations result))]
    (is (= ::sc/invariant-violated (get-in violation [:event :type])))
    (is (= :bad (get-in violation [:event :state :_state])))
    (is (= #{[:bad]} (get-in violation [:event :state ::sc/violated-states])))
    (is (some #{:unsafe} (:path violation)))
    (is (nil? (:impossible result)))
    (is (not (:truncated result)))))

(deftest forbidden-transient-entry-is-not-lost-during-stabilization
  (let [result (check/check
                {:bthreads
                 {:chart (sc/bthread
                          {:id :transient :initial :start
                           :states {:start {::sc/bid {:request #{:go}}
                                            :on {:go :bad}}
                                    :bad {::sc/invariant-violated true
                                          :always :apparently-safe}
                                    :apparently-safe
                                    {::sc/bid {:request #{{:type :done :terminal true}}}}}})}
                 :max-nodes 10})
        violation (first (:safety-violations result))]
    (is (= ::sc/invariant-violated (get-in violation [:event :type])))
    (is (= :apparently-safe (get-in violation [:event :state :_state])))
    (is (= #{[:bad]} (get-in violation [:event :state ::sc/violated-states])))))

(deftest bid-function-exceptions-are-visible-to-the-checker
  (let [result (check/check
                {:bthreads
                 {:chart (sc/bthread
                          {:id :throws :initial :start
                           :states {:start {::sc/bid {:request #{:go}}
                                            :on {:go :broken}}
                                    :broken {::sc/bid (fn [_]
                                                       (throw (ex-info "broken bid" {})))}}})}
                 :max-nodes 10})
        violation (first (:safety-violations result))]
    (is (= ::b/unhandled-step-fn-error (get-in violation [:event :type])))
    (is (true? (get-in violation [:event :terminal])))
    (is (not (:truncated result)))))
