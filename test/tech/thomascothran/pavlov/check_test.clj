(ns tech.thomascothran.pavlov.check-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.check :as check]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.bprogram.ephemeral-test.bthreads :as tb]))

(defn all-moves
  "request all combinations of x moves"
  [players]
  (->> (for [x-coord  [0 1 2]
             y-coord  [0 1 2]
             player   players]
         [x-coord y-coord player])
       (map #(hash-map :type %))))

(defn x-moves-bthread
  []
  (b/reprise {:request (into [] (all-moves [:x]))}))

(comment
  (-> (b/bid (x-moves-bthread) nil)
      (b/bid nil)
      (b/bid nil)))

(defn make-naive-strategy
  []
  (b/reprise {:request (into [] (all-moves [:o]))}))

(defn initial-tic-tac-toe-bthreads
  []
  (reduce into []
          [(mapv tb/make-winning-bthreads tb/winning-event-set)
           [(tb/make-enforce-turn-bthreads)]
           (tb/make-no-double-placement-bthreads)
           [(make-naive-strategy)]]))

(comment
  (mapv b/name (initial-tic-tac-toe-bthreads)))

(def x-win-paths
  (into #{};; for each winning path set
        (filter (comp #(= :x %) last first))
        tb/winning-event-set))

(defn make-losing-safety-bthread
  "terminate the program as violating a safety property
  if player wins"
  [winning-path]
  (b/step
   [::we-lost-bthreads winning-path]
   (fn [{:keys [remaining-events] :as acc} event]
     (let [event-type (event/type event)
           remaining-events' (disj remaining-events event-type)
           events-to-watch
           (into #{} (map (fn [event] {:type event})
                          winning-path))
           default-bid {:wait-on events-to-watch}]

       (cond (nil? event) ;; event is nil on initialization
             [{:remaining-events (set winning-path)} default-bid]

             ;; Terminate - they won :-(
             (= remaining-events #{event-type})
             [{:remaining-events remaining-events'}
              {:request #{{:type :x-won
                           :invariant-violated true
                           :terminal true}}}]

             :else
             [(update acc :remaining-events disj (event/type event))
              default-bid])))))

(defn losing-bthreads
  []
  (mapv make-losing-safety-bthread x-win-paths))

(defn safety-bthreads
  []
  (losing-bthreads))

(deftest test-tic-tac-toe-x-wins
  (let [{:keys [event path]}
        (check/run {:safety-bthreads (safety-bthreads)
                    :check-deadlock true
                    :bthreads (initial-tic-tac-toe-bthreads)
                    :events (all-moves [:x])})

        {:keys [invariant-violated] event-type :type} event]

    (is invariant-violated)

    (is (= :x-won event-type))

    (is (= [{:type [0 0 :x]}
            {:type [0 1 :o]}
            {:type [0 2 :x]}
            {:type [1 0 :o]}
            {:type [1 1 :x]}
            {:type [1 2 :o]}
            {:type [2 0 :x]}
            {:invariant-violated true, :terminal true, :type :x-won}]
           path))))

(deftest test-run-deadlock
  (let [bthreads [{:wait-on #{:godot}}]
        events [{:type [:beckett :writes]}]

        result (check/run {:bthreads bthreads
                           :events events
                           :check-deadlock true})
        event (get result :event)
        path (get result :path)]

    (is (= ::check/deadlock (get event :type)))
    (is (= 2 (count path)))))

(deftest test-safety-properties-are-checked
  (let [bthreads [{:request #{:godot}}]

        safety-bthreads
        [(b/bids [{:wait-on #{:godot}}
                  {:request [{:type :oh!
                              :terminal true
                              :invariant-violated true}]}])]

        result (check/run {:bthreads bthreads
                           :check-deadlock true
                           :safety-bthreads safety-bthreads})
        event (get result :event)
        path (get result :path)]

    (is (= :oh! (get event :type)))
    (is (= true (get event :invariant-violated)))
    (is (= 2 (count path)))))

(deftest test-happy-path
  (let [bthreads
        [(b/bids [{:request #{:a}}
                  {:request [:b]}])
         (b/bids [{:wait-on #{:b}}
                  {:request [{:type :c
                              :terminal true}]}])]

        safety-bthreads
        [(b/bids [{:wait-on #{:z}}
                  {:request [{:type :no!
                              :terminal true
                              :invariant-violated true}]}])]

        events [{:type :a}]

        result (check/run {:bthreads bthreads
                           :events events
                           :check-deadlock true
                           :safety-bthreads safety-bthreads})]
    (is (nil? result))))
