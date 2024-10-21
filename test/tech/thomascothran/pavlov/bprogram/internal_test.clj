(ns tech.thomascothran.pavlov.bprogram.internal-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.bthread :as bthread]
            [tech.thomascothran.pavlov.defaults]
            [tech.thomascothran.pavlov.bprogram.proto :as bp]
            [tech.thomascothran.pavlov.bprogram.internal :as bpi]
            [tech.thomascothran.pavlov.event.proto :as event]))

(deftest good-morning-and-evening
  (let [bthreads
        [(bthread/seq (repeat 4 {:request #{:good-morning}})
                      {:priority 1})

         (bthread/seq (repeat 4 {:request #{:good-evening}}))
         (bthread/seq (interleave
                       (repeat {:wait-on #{:good-morning}
                                :block #{:good-evening}})
                       (repeat {:wait-on #{:good-evening}
                                :block #{:good-morning}})))]
        program   (bpi/make-program! bthreads)
        out-queue (:out-queue program)
        _         @(bp/stop! program)]
    (comment (bp/kill! program))
    (is (= (interleave (repeat 4 :good-morning)
                       (repeat 4 :good-evening))
           (seq out-queue)))))

(def straight-wins-paths
  (let [product
        (for [x (range 3)
              y (range 3)]
          [x y])

        vertical
        (partition 3 product)

        horizontal
        (->> (sort-by second product)
             (partition 3))]
    (reduce into [] [vertical horizontal])))

(def crossing-win-bthreads
  [(map vector [0 1 2] [0 1 2])
   (map vector [2 1 0] [0 1 2])])

(def winning-paths
  (into crossing-win-bthreads straight-wins-paths))

(def winning-event-set
  (for [paths winning-paths
        player [:x :o]]
    (into #{} (map #(conj % player)) paths)))

(defn make-winning-bthreads
  "for a winning path (e.g., three diagonal squares
  selected by the same player), emit a win event
  and terminate the pogram."
  [path-events]
  (bthread/reduce
   (fn [{:keys [remaining-events] :as acc} event]
     (if event ;; event is nil on initialization
       (let [event-type (event/type event)]
         (if (= remaining-events #{event-type})
           {:request #{{:type [(last event-type) :wins]
                        :terminal true}}
            :remaining-events #{}}
           (update acc :remaining-events disj event-type)))
       acc))
   ;; Initial value
   {:remaining-events (set path-events)
    :wait-on (into #{}
                   (map (fn [event] {:type event}))
                   path-events)}))

;; Note that we test our behavioral threads in isolation
;; from the bprogram.
(deftest test-winning-bthreads
  (testing "Given a bthread that watches a crossing win pattern for player x
    When that crossing pattern is filled in by player x
    Then the bthread requests a win event"
    (let [bthread (make-winning-bthreads
                   #{[0 0 :x] [2 2 :x] [1 1 :x]})
          bid1 (bthread/bid bthread {:type [1 1 :x]})
          bid2 (bthread/bid bthread {:type [2 2 :x]})
          bid3 (bthread/bid bthread {:type [0 0 :x]})]
      (is (= #{[0 0 :x] [2 2 :x]} (:remaining-events bid1))
          "Track which events are left to reach a win for x after the first move")
      (is (= #{[0 0 :x]} (:remaining-events bid2))
          "Track which events are left to reach a win for x after the second move")
      (is (= #{{:type [:x :wins] :terminal true}} (:request bid3))
          "Request a win when all the winning moves have been made"))))

(deftest tic-tac-toe
  (let [bthreads (mapv make-winning-bthreads winning-event-set)
        events   [{:type [0 0 :o]}
                  {:type [1 1 :o]}
                  {:type [2 2 :o]}]
        program  (bpi/make-program! bthreads)
        _        (doseq [event events]
                   (bp/submit-event! program event))
        out-queue (:out-queue program)
        _        @(bp/stop! program)]
    (is (= (conj events {:terminal true, :type [:o :wins]})
           (take 5 (seq out-queue))))))


