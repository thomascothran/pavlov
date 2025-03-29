(ns tech.thomascothran.pavlov.bprogram.ephemeral-test.bthreads
  (:require
   [tech.thomascothran.pavlov.bthread :as b]
   [tech.thomascothran.pavlov.defaults]
   [tech.thomascothran.pavlov.event :as event]))

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
   [::make-winning-bthreads path-events]
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
             [(update acc :remaining-events disj event-type) default-bid])))))

;; Now we need to handle moves.
;; But we need some rules.
;; First, you can't pick the same square
(defn make-no-double-placement-bthreads
  "You can't pick another player's square!"
  []
  (for [x-coordinate [0 1 2]
        y-coordinate [0 1 2]]
    (b/bids
     ::no-double-placement
     [{:wait-on #{[x-coordinate y-coordinate :x]
                  [x-coordinate y-coordinate :o]}}
      {:block #{[x-coordinate y-coordinate :x]
                [x-coordinate y-coordinate :o]}}])))

(defn make-computer-picks-bthreads
  "Without worrying about strategy, let's pick a square"
  [player]
  (b/bids ::computer-picks
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

    (b/interlace ::enforce-turns
                 [{:wait-on x-moves
                   :block o-moves}
                  {:wait-on o-moves
                   :block x-moves}])))

;; Notice that this rule could be generalized.
;; It could take the players and coordinates as parameters
;; and then be used for *any* turn based game. Chess,
;; checkers, poker, etc.
