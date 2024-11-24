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
        !a        (atom [])
        listener  (fn [x _] (swap! !a conj x))
        program   (bpi/make-program! bthreads
                                     {:listeners {:test listener}})
        _         @(bp/stop! program)]
    (is (= (interleave (repeat 4 :good-morning)
                       (repeat 4 :good-evening))
           (butlast @!a)))))

(deftest add-listener
  (let [bthreads [(bthread/seq [{:wait-on #{:go}}
                                {:request #{:some-event}}])]

        !a        (atom [])
        listener  (fn [x _] (swap! !a conj x))
        program   (bpi/make-program! bthreads)
        _         (bp/listen! program :test listener)
        _         (bp/submit-event! program :go)
        _         @(bp/stop! program)]
    (is (= [:go :some-event]
           (butlast  @!a)))))

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
                   path-events)}
   {:priority 1})) ;; overrides other bids

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

;; Let's see if it can detect a win!
;; We'll ignore player moves for now.
(deftest tic-tac-toe-simple-win
  (let [bthreads (mapv make-winning-bthreads winning-event-set)
        events   [{:type [0 0 :o]}
                  {:type [1 1 :o]}
                  {:type [2 2 :o]}]
        !a        (atom [])
        listener  (fn [x _] (swap! !a conj x))
        program  (bpi/make-program! bthreads
                                    {:listeners {:test listener}})
        _        (doseq [event events]
                   (bp/submit-event! program event))
        _        @(bp/stop! program)]
    (is (= (conj events {:terminal true, :type [:o :wins]})
           (take 5 @!a)))))

;; Now we need to handle moves.
;; But we need some rules.
;; First, you can't pick the same square
(defn make-no-double-placement-bthreads
  "You can't pick another player's square!"
  []
  (for [x-coordinate [0 1 2]
        y-coordinate [0 1 2]
        player [:x :o]]
    (bthread/seq
     [{:wait-on #{[x-coordinate y-coordinate player]}}
      {:block #{[x-coordinate y-coordinate (if (= player :x) :o :x)]}}])))

(defn make-computer-picks-bthreads
  "Without worrying about strategy, let's pick a square"
  [player]
  (bthread/seq
   (for [x-coordinate [0 1 2]
         y-coordinate [0 1 2]]
     {:request #{{:type [x-coordinate y-coordinate player]}}})))

;; But wait? Doesn't `make-computer-picks` need to account for
;; the squares that are already occupied? 
;;
;; Nope! the no double placement bthread takes care of that for us.
;;
;; OK, but won't we have to rewrite it when we take strategy into 
;; account, e.g., picking the winning square or blocking the other
;; player?
;;
;; Nope! We can add strategies incrementally and prioritize them.

(deftest test-simple-computer-picks
  (let [bthreads
        (reduce into [(make-computer-picks-bthreads :o)
                      (bthread/seq             ;; should be blocked
                       [{:type [0 0 :o]}])]
                [(mapv make-winning-bthreads winning-event-set)
                 (make-no-double-placement-bthreads)])

        !a        (atom [])
        listener  (fn [x _] (swap! !a conj x))
        program (bpi/make-program! bthreads
                                   {:listeners {:test listener}})

        _        @(bp/stop! program)
        out-events @!a]
    (is (= 4 (count out-events)))
    (is (= #{:o} (->> out-events
                      (take 3)
                      (mapv (comp last event/type))
                      set))
        "The first three events should be o moves")
    (is (= [:o :wins]
           (event/type (last out-events))))))

;; Great! 
;; We were able to get our computer to make moves. 
;; But it's just going to keep picking without waiting for
;; the other player!
;; We need a bthread that enforces turns.

(defn make-enforce-turn-bthreads
  []
  (let [moves (for [x-coord [0 1 2]
                    y-coord [0 1 2]
                    player [:x :o]]
                [x-coord y-coord player])

        x-moves
        (into #{}
              (comp (filter (comp (partial = :x) last)))
              moves)

        o-moves
        (into #{}
              (comp (filter (comp (partial = :o) last)))
              moves)]

    (bthread/seq
     (interleave (repeat {:wait-on x-moves
                          :block o-moves})
                 (repeat {:wait-on o-moves
                          :block x-moves})))))

;; Notice that this rule could be generalized.
;; It could take the players and coordinates as parameters
;; and then be used for *any* turn based game. Chess,
;; checkers, poker, etc.

(deftest test-taking-turns
  ;; Problem is that blocked events
  ;; are being represented both as a map with a :type key
  ;; and as the type itself. Can we support both?
  (let [bthreads
        (reduce into [(make-computer-picks-bthreads :o)
                      (make-enforce-turn-bthreads)]
                [(mapv make-winning-bthreads winning-event-set)
                 (make-no-double-placement-bthreads)])

        !a        (atom [])
        listener  (fn [x _] (swap! !a conj x))
        program (bpi/make-program! bthreads
                                   {:listeners {:test listener}})
        _        (bp/submit-event! program {:type [1 1 :x]})
        _        @(bp/stop! program)]

    (is (= [{:type [1 1 :x]} {:type [0 0 :o]}]
           (butlast @!a)))))
