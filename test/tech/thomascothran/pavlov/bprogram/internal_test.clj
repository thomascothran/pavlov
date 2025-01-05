(ns tech.thomascothran.pavlov.bprogram.internal-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.defaults]
            [tech.thomascothran.pavlov.bprogram.proto :as bp]
            [tech.thomascothran.pavlov.bprogram.internal :as bpi]
            [tech.thomascothran.pavlov.event :as event]))

(deftest subscriber-should-receive-event-after-bthread-executes
  (let [!stack  (atom [])
        bthread (b/step
                 ::test-subscriber-call-order
                 (fn [_ event]
                   (swap! !stack conj [:bthread event])
                   [nil {:wait-on #{:test-event}}]))
        subscriber (fn [x _] (swap! !stack conj [:subscriber x]))
        program   (bpi/make-program! [bthread]
                                     {:subscribers {:test subscriber}})
        _ (bp/submit-event! program :test-event)
        _ @(bp/stop! program)]
    (is (= [[:bthread nil]
            [:bthread :test-event]
            [:subscriber :test-event]]
           (butlast @!stack)))))

(deftest good-morning-and-evening
  (let [bthreads
        [(b/reprise 4
                    {:request #{:good-morning}}
                    {:priority 1})

         (b/reprise 4 {:request #{:good-evening}})
         (b/interlace
          [{:wait-on #{:good-morning}
            :block #{:good-evening}}
           {:wait-on #{:good-evening}
            :block #{:good-morning}}])]

        !a        (atom [])
        subscriber  (fn [x _] (swap! !a conj x))
        program   (bpi/make-program! bthreads
                                     {:subscribers {:test subscriber}})
        _         @(bp/stop! program)]
    (is (= (interleave (repeat 4 :good-morning)
                       (repeat 4 :good-evening))
           (butlast @!a)))))

(deftest add-subscriber
  (let [bthreads [(b/seq [{:wait-on #{:go}}
                          {:request #{:some-event}}])]

        !a         (atom [])
        subscriber (fn [x _] (swap! !a conj x))
        program    (bpi/make-program! bthreads)
        _          (bp/subscribe! program :test subscriber)
        _          (bp/submit-event! program :go)
        _          @(bp/stop! program)]
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
  (b/step
   ::make-winning-bthreads
   (fn [{:keys [remaining-events] :as acc} event]
     (let [event-type (event/type event)
           remaining-events' (disj remaining-events event-type)
           events-to-watch
           (into #{} (map (fn [event] {:type event})
                          path-events))
           default-bid {:wait-on events-to-watch}]
       (cond (nil? event) ;; event is nil on initialization
             [{:remaining-events (set path-events)} default-bid]

             ;; Terminate - we've won!
             (= remaining-events #{event-type})
             [{:remaining-events remaining-events'}
              {:request #{{:type [(last event-type) :wins]
                           :terminal true}}}]

             :else
             [(update acc :remaining-events disj event-type) default-bid])))
   {:priority 1})) ;; overrides other bids

;; Note that we test our behavioral threads in isolation
;; from the bprogram.
(deftest test-winning-bthreads
  (testing "Given a bthread that watches a crossing win pattern for player x
    When that crossing pattern is filled in by player x
    Then the bthread requests a win event"
    (let [bthread (make-winning-bthreads
                   #{[0 0 :x] [2 2 :x] [1 1 :x]})
          bid1 (b/bid bthread nil) ;; initialization
          bid2 (b/bid bthread {:type [1 1 :x]})
          bid3 (b/bid bthread {:type [2 2 :x]})
          bid4 (b/bid bthread {:type [0 0 :x]})]

      (is (= #{:wait-on}
             (set (keys bid1))
             (set (keys bid2))
             (set (keys bid3)))
          "The first three bids should just wait")
      (is (= #{{:type [:x :wins] :terminal true}}
             (:request bid4))
          "The last bid should request a win, because all the winning moves have been made"))))

;; Let's see if it can detect a win!
;; We'll ignore player moves for now.
(deftest tic-tac-toe-simple-win
  (let [bthreads (mapv make-winning-bthreads winning-event-set)
        events   [{:type [0 0 :o]}
                  {:type [1 1 :o]}
                  {:type [2 2 :o]}]
        !a        (atom [])
        subscriber  (fn [x _] (swap! !a conj x))
        program  (bpi/make-program! bthreads
                                    {:subscribers {:test subscriber}})
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
    (b/seq
     [{:wait-on #{[x-coordinate y-coordinate player]}}
      {:block #{[x-coordinate y-coordinate (if (= player :x) :o :x)]}}])))

(defn make-computer-picks-bthreads
  "Without worrying about strategy, let's pick a square"
  [player]
  (b/seq
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
                      (b/seq             ;; should be blocked
                       [{:type [0 0 :o]}])]
                [(mapv make-winning-bthreads winning-event-set)
                 (make-no-double-placement-bthreads)])

        !a        (atom [])
        subscriber  (fn [x _] (swap! !a conj x))
        program (bpi/make-program! bthreads
                                   {:subscribers {:test subscriber}})

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

    (b/interlace [{:wait-on x-moves
                   :block o-moves}
                  {:wait-on o-moves
                   :block x-moves}])))

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
        subscriber (fn [x _] (swap! !a conj x))
        program (bpi/make-program! bthreads
                                   {:subscribers {:test subscriber}})
        _        (bp/submit-event! program {:type [1 1 :x]})
        _        @(bp/stop! program)]

    (is (= [{:type [1 1 :x]} {:type [0 0 :o]}]
           (butlast @!a)))))
