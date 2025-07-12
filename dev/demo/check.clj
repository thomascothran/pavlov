(ns demo.check
  "Show how model checking works"
  (:require [clojure.repl.deps :refer [add-lib]]
            [tech.thomascothran.pavlov.bprogram.ephemeral-test.bthreads :as tb]
            [tech.thomascothran.pavlov.check :as check]
            [tech.thomascothran.pavlov.subscribers.tap :as tap]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.bthread :as b]))

(add-lib 'org.clojure/math.combinatorics {:mvn/version "0.3.0"})

(require '[clojure.math.combinatorics :as combo])

;; Helper functions
(defn all-moves
  "request all combinations of tic-tac toe moves"
  [players]
  (->> (for [x-coord [0 1 2]
             y-coord [0 1 2]
             player players]
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
          [(map-indexed (fn [idx event-set]
                          [[:winning-bthread idx]
                           (tb/make-winning-bthreads event-set)])
                        tb/winning-event-set)
           [[::enforce-turns (tb/make-enforce-turn-bthreads)]]
           (tb/make-no-double-placement-bthreads)
           [[::draw (tb/make-draw-bthread)]]
           ;; computer strategy
           [[::naive-strategy (make-naive-strategy)]]]))

(defn initial-tic-tac-toe-bthreads
  []
  (make-tic-tac-toe-rules-bthreads))

;; Let's make a safety bthread
(defn make-losing-safety-bthread
  "terminate the program as violating a safety property
  if player wins"
  [winning-path]
  (b/step
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
  (map-indexed (fn [idx path]
                 [[:losing-bthread idx]
                  (make-losing-safety-bthread path)])
               x-win-paths))

(def first-result
  "The first time, x winds with a crosser

   | x | o | x |
   | o | x |   |
   | x | o |   |

   Note even though both our safety bthreads and our
   win detector bthreads bid win X takes the winning
   square, but the safety bthreads have the higher
   priority and terminate the program
  "
  (check/run {:safety-bthreads (losing-bthreads)
              :check-deadlock true
              :make-bthreads #(initial-tic-tac-toe-bthreads)
              :events (all-moves [:x])}))

;; We need to improve our strategy. Any time X
;; can win by 3 across, we need to block it.
(defn make-block-crosser-bthread
  [player]
  (let [r->l #{[0 0 player] [1 1 player] [2 2 player]}
        l->r #{[2 0 player] [1 1 player] [0 2 player]}]
    (b/step
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

;; Yay, we get a draw!
(def second-result
  (check/run {:safety-bthreads (losing-bthreads)
              :check-deadlock true
              :subscribers {:tap tap/subscriber}
              :make-bthreads #(into [[::block-crosser (make-block-crosser-bthread :x)]]
                                    (initial-tic-tac-toe-bthreads))
              :events (all-moves [:x])}))

;; But we're only testing against one set of requests
;; x can make. We want to test them all!
;; for that we need a strategy

(def third-result
  "now we have a tricky case.

   Final state

   X | O | X |
   -----------
   O | X |   |
   -----------
   X |   | O |

   Rewind a few moves...
   X | O |   |  ;; <- we should have taken the middle!, not 1 0
   -----------
   O |   |   |
   -----------
   X |   |   |
  "
  (check/run {:safety-bthreads (losing-bthreads)
              :check-deadlock true
              :strategy combo/permutations ;; <- new!
              :subscribers {:tap tap/subscriber}
              :make-bthreads #(into [[::block-crosser (make-block-crosser-bthread :x)]]
                                    (initial-tic-tac-toe-bthreads))
              :events (all-moves [:x])}))

 ;; Simple center control strategy
(defn make-center-strategy
  "Prioritize taking the center square [1 1]"
  []
  (b/bids [{:request #{{:type [1 1 :o]}}}]))

(def fourth-result
  "Adding center control strategy"
  (check/run {:safety-bthreads (losing-bthreads)
              :check-deadlock true
              :strategy combo/permutations
              :subscribers {:tap tap/subscriber}
              :make-bthreads #(into [[::center-strategy (make-center-strategy)] ;; <- center first!
                                     [::block-crosser (make-block-crosser-bthread :x)]]
                                    (initial-tic-tac-toe-bthreads))
              :events (all-moves [:x])}))

 ;; Block horizontal and vertical lines
(defn make-block-lines-bthread
  "Block X from winning with horizontal or vertical lines"
  [player]
  (let [rows (for [y [0 1 2]]
               #{[0 y player] [1 y player] [2 y player]})
        cols (for [x [0 1 2]]
               #{[x 0 player] [x 1 player] [x 2 player]})
        all-lines (concat rows cols)]
    (b/step
     (fn [{:keys [remaining-lines] :as _state} event]
       (let [event-type (event/type event)
             ;; Update remaining squares for each line
             remaining-lines' (map #(disj % event-type) remaining-lines)
             change-player (fn [[x-coord y-coord player]]
                             [x-coord y-coord
                              ({:x :o :o :x} player)])
             ;; Find lines where player has 2 of 3 squares
             blocking-moves
             (into #{} (comp (filter (comp (partial = 1) count))
                             (map first)
                             (map change-player))
                   remaining-lines')

             next-state {:remaining-lines remaining-lines'}

             all-line-squares (apply clojure.set/union all-lines)
             default-bid {:wait-on all-line-squares
                          :request blocking-moves}

             result
             (if (nil? event) ;; initialize
               [{:remaining-lines all-lines} default-bid]
               [next-state default-bid])]
         result)))))

(def fifth-result
  "Added row/column blocking to prevent X from winning with straight lines.

   Result: X still wins by creating a fork!

   Final board:
     x | o |
   -----------
       | o | x
   -----------
     x | x | x  <- X wins bottom row

   Critical moment after X plays [2,1]:
     x |   |
   -----------
       | o | x  <- X just played here
   -----------
     x |   |

   X created 3 simultaneous threats:
   1. Diagonal: [0,0] -> [1,1] -> [2,2]
   2. Right column: [2,0] -> [2,1] -> [2,2]
   3. Bottom row: [0,2] -> [1,2] -> [2,2]

   O could only block one threat. This shows we need fork prevention!"
  (check/run {:safety-bthreads (losing-bthreads)
              :check-deadlock true
              :strategy combo/permutations
              :subscribers {:tap tap/subscriber}
              :make-bthreads #(into [[::center-strategy (make-center-strategy)]
                                     [::block-lines (make-block-lines-bthread :x)]
                                     [::block-crosser (make-block-crosser-bthread :x)]]
                                    (initial-tic-tac-toe-bthreads))
              :events (all-moves [:x])}))

 ;; Prevent corner-based forks
(defn make-prevent-corner-forks-bthread
  "Prevent X from creating forks using opposite-side corners"
  []
  (let [;; Define the dangerous corner pairs and their blocking moves
        ;; If X has both corners in a pair, O must take the blocking edge
        corner-fork-patterns
        [{:corners #{[0 0 :x] [0 2 :x]} :block [2 1 :o]} ;; top corners -> bottom edge
         {:corners #{[0 0 :x] [2 0 :x]} :block [1 2 :o]} ;; left corners -> right edge
         {:corners #{[2 0 :x] [2 2 :x]} :block [0 1 :o]} ;; bottom corners -> top edge
         {:corners #{[0 2 :x] [2 2 :x]} :block [1 0 :o]} ;; right corners -> left edge
         ;; Also handle the diagonal opposite corners
         {:corners #{[0 0 :x] [2 2 :x]} :block [1 0 :o]} ;; main diag -> edge
         {:corners #{[0 2 :x] [2 0 :x]} :block [0 1 :o]}]] ;; anti diag -> edge
    (b/step
     (fn [{:keys [x-moves] :as state} event]
       (let [event-type (event/type event)
             ;; Track X's moves
             x-moves' (if (and event-type
                               (vector? event-type)
                               (= :x (last event-type)))
                        (conj (or x-moves #{}) event-type)
                        x-moves)

             ;; Check each pattern to see if we need to block
             blocking-moves
             (into #{}
                   (comp (filter (fn [{:keys [corners]}]
                                   (clojure.set/subset? corners x-moves')))
                         (map :block))
                   corner-fork-patterns)

             ;; Wait on all X corner moves
             all-x-corners (for [x [0 2] y [0 2]] [x y :x])

             bid (cond
                   (nil? event) ;; initialization
                   {:wait-on (set all-x-corners)}

                   (seq blocking-moves)
                   {:wait-on (set all-x-corners)
                    :request blocking-moves}

                   :else
                   {:wait-on (set all-x-corners)})]

         [{:x-moves x-moves'} bid])))))

(def sixth-result
  "Added corner fork prevention to block X from creating multi-threat positions.

   Result: X still wins due to a PRIORITIZATION BUG!

   Final board:
     x | x | x  <- X wins top row
   -----------
     x | o |
   -----------
     o | o | o

   Game sequence:
   1. X: [0,0] (top-left)
   2. O: [1,1] (center)
   3. X: [0,1] (left-middle)
   4. O: [0,2] (bottom-left) - blocks left column
   5. X: [2,0] (top-right) - threatens top row!
   6. O: [1,2] (bottom-middle) - WRONG! Should play [1,0]
   7. X: [1,0] (top-middle) - WIN!

   The bug: Two bthreads conflicted at move 6:
   - block-lines correctly wanted [1,0] to stop the top row
   - prevent-corner-forks wanted [1,2] to prevent a 'fork'

   Fork prevention had higher priority (listed first in make-bthreads)
   but blocking immediate threats should take precedence!"
  (check/run {:safety-bthreads (losing-bthreads)
              :check-deadlock true
              :strategy combo/permutations
              :subscribers {:tap tap/subscriber}
              :make-bthreads #(into [[::center-strategy (make-center-strategy)]
                                     [::prevent-corner-forks (make-prevent-corner-forks-bthread)]
                                     [::block-lines (make-block-lines-bthread :x)]
                                     [::block-crosser (make-block-crosser-bthread :x)]]
                                    (initial-tic-tac-toe-bthreads))
              :events (all-moves [:x])}))

(comment
  (mapv event/type
        [{:type [0 0 :x]}
         {:type [1 1 :o]}
         {:type [0 1 :x]}
         [0 2 :o]
         {:type [2 0 :x]}
         [1 2 :o]
         {:type [1 0 :x]}
         {:type :x-won, :invariant-violated true, :terminal true}]))
