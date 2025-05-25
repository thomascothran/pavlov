(ns demo.check
  "Show how model checking works"
  (:require [clojure.math.combinatorics :as combo]
            [tech.thomascothran.pavlov.bprogram.ephemeral-test.bthreads :as tb]
            [tech.thomascothran.pavlov.check :as check]
            [tech.thomascothran.pavlov.subscribers.tap :as tap]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.bthread :as b]))

;; Helper functions
(defn all-moves
  "request all combinations of tic-tac toe moves"
  [players]
  (->> (for [x-coord  [0 1 2]
             y-coord  [0 1 2]
             player   players]
         [x-coord y-coord player])
       (map #(hash-map :type %))))

(comment
  (take 5 (all-moves [:x :o])))

(defn request-all-moves-bthread
  [player]
  (assert (#{:x :o} player))
  {:request (into [] (all-moves [player]))})

;; We'll be player o.
;; At first, we just request all squares for o
(defn make-naive-strategy
  []
  (request-all-moves-bthread :o))

(defn make-tic-tac-toe-rules-bthreads
  []
  (reduce into []
          [(mapv tb/make-winning-bthreads tb/winning-event-set)
           [(tb/make-enforce-turn-bthreads)]
           (tb/make-no-double-placement-bthreads)
           [(tb/make-draw-bthread)]
           ;; computer strategy
           [(make-naive-strategy)]]))

(defn initial-tic-tac-toe-bthreads
  []
  (into (make-tic-tac-toe-rules-bthreads)
        [(make-naive-strategy)]))

;; Let's make a safety bthread
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

       (cond (nil? event) ;; initialization
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

;; All possible winning moves for X
(def x-win-paths
  (into #{};; for each winning path set
        (filter (comp #(= :x %) last first))
        tb/winning-event-set))

(defn losing-bthreads
  []
  (mapv make-losing-safety-bthread x-win-paths))

(comment
  ;; The first time, x winds with a crosser
  ;; | x | o | x |
  ;; | o | x |   |
  ;; | x | o |   |
  ;;
  ;; Note even though both our safety bthreads and our
  ;; win detector bthreads bid win X takes the winning
  ;; square, but the safety bthreads have the higher
  ;; priority and terminate the program
  (def first-result
    (check/run {:safety-bthreads (losing-bthreads)
                :check-deadlock true
                :make-bthreads #(initial-tic-tac-toe-bthreads)
                :events (all-moves [:x])})))

;; We need to improve our strategy. Any time X
;; can win by 3 across, we need to block it.

(defn make-block-crosser-bthread
  [player]
  (let [r->l #{[0 0 player] [1 1 player] [2 2 player]}
        l->r #{[2 0 player] [1 1 player] [0 2 player]}]
    (b/step
     [::block-crosser player]
     (fn [{:keys [remaining-r->l
                  remaining-l->r] :as _state}
          event]
       (let [event-type (event/type event)
             remaining-r->l' (disj remaining-r->l event-type)
             remaining-l->r' (disj remaining-l->r event-type)
             change-player (fn [[x-coord y-coord player]]
                             [x-coord y-coord
                              ({:x :o :o :x} player)])
             next-state
             {:remaining-r->l remaining-r->l'
              :remaining-l->r remaining-l->r'}

             winning-moves
             (into #{} (comp (filter (comp (partial = 1)
                                           count))
                             (map first)
                             (map change-player))
                   [remaining-r->l'
                    remaining-l->r'])

             default-bid {:wait-on (into r->l l->r)
                          :request winning-moves}
             result
             (if (nil? event) ;; initialize
               [{:remaining-r->l r->l :remaining-l->r l->r}
                default-bid]
               [next-state default-bid])]

         result)))))

(comment
  ;; Yay, we get a draw!
  (def second-result
    (check/run {:safety-bthreads (losing-bthreads)
                :check-deadlock true
                :subscribers {:tap tap/subscriber}
                :make-bthreads #(into [(make-block-crosser-bthread :x)]
                                      (initial-tic-tac-toe-bthreads))
                :events (all-moves [:x])})))

;; But we're only testing against one set of requests
;; x can make. We want to test them all!
;; for that we need a strategy

(comment
  (def third-result
    ;; Aha, we need to prevent x winning on straight paths as well!
    (check/run {:safety-bthreads (losing-bthreads)
                :check-deadlock true
                :strategy combo/permutations ;; <- new!
                :subscribers {:tap tap/subscriber}
                :make-bthreads #(into [(make-block-crosser-bthread :x)]
                                      (initial-tic-tac-toe-bthreads))
                :events (all-moves [:x])})))

(defn make-block-straight-winning-paths
  "Block `player`'s winning paths that are
  horizontal or vertical lines"
  [player]
  (b/step [::block-straight-winning-paths player]
          (fn [{:keys [remaining-events]} event]
            (let [vertical
                  (for [x [0 1 2]]
                    (for [y [0 1 2]]
                      [x y player]))

                  horizontal
                  (for [y [0 1 2]]
                    (for [x [0 1 2]]
                      [x y player]))

                  all-straight-winning-paths
                  (reduce into #{} [vertical horizontal])

                  event-type (event/type event)]
              (cond (nil? event)
                    [{:remaining-events all-straight-winning-paths}
                     {:wait-on all-straight-winning-paths}]

                    (= remaining-events #{event-type})
                    [{:remaining-events (disj remaining-events event-type)}
                     {:request #{{:type :x-won
                                  :terminal true}}}]
                    :else
                    [{:remaining-events (disj remaining-events event-type)}
                     {:wait-on all-straight-winning-paths}])))))

(comment
  "now we can test the bthread in isolation")
