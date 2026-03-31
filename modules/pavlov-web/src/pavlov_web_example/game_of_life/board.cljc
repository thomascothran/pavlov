(ns pavlov-web-example.game-of-life.board
  (:require [clojure.set :as set]
            [tech.thomascothran.pavlov.bthread :as b]))

(defn- board-event-types
  []
  #{:game-of-life/cell-clicked
    :game-of-life/reset-clicked
    :game-of-life/advance-generation
    :game-of-life/get-board-snapshot})

(defn- all-coords
  [height width]
  (for [row (map str (range height))
        col (map str (range width))]
    [row col]))

(defn- in-bounds?
  [height width row col]
  (and (<= 0 row)
       (< row height)
       (<= 0 col)
       (< col width)))

(def ^:private neighbor-offsets
  [[-1 -1] [-1 0] [-1 1]
   [0 -1]          [0 1]
   [1 -1]  [1 0]   [1 1]])

(defn- neighbor-coords
  [height width [row col]]
  (let [row' (Long/parseLong row)
        col' (Long/parseLong col)]
    (keep (fn [[row-offset col-offset]]
            (let [neighbor-row (+ row' row-offset)
                  neighbor-col (+ col' col-offset)]
              (when (in-bounds? height width neighbor-row neighbor-col)
                [(str neighbor-row) (str neighbor-col)])))
          neighbor-offsets)))

(defn- live-neighbor-count
  [height width live-cells coords]
  (count (filter live-cells (neighbor-coords height width coords))))

(defn- evolve-live-cells
  [height width live-cells]
  (into #{}
        (for [coords (all-coords height width)
              :let [live-neighbors (live-neighbor-count height width live-cells coords)
                    alive? (contains? live-cells coords)]
              :when (if alive?
                      (<= 2 live-neighbors 3)
                      (= 3 live-neighbors))]
          coords)))

(defn- cell-coords
  [event]
  [(:pavlov-row event) (:pavlov-col event)])

(defn- initial-state []
  {:live-cells #{}
   :generation 0})

(defn- board-changed-event
  [height width prev-state next-state]
  {:type :game-of-life/board-changed
   :height height
   :width width
   :created-cells (set/difference (:live-cells next-state) (:live-cells prev-state))
   :killed-cells (set/difference (:live-cells prev-state) (:live-cells next-state))
   :generation (:generation next-state)
   :live-count (count (:live-cells next-state))})

(defn- board-snapshot-event
  [height width state client-id]
  {:type :game-of-life/board-snapshot
   :client-id client-id
   :height height
   :width width
   :live-cells (:live-cells state)
   :generation (:generation state)
   :live-count (count (:live-cells state))})

(defn make-board-bthread
  [height width]
  (b/step
   (fn [state incoming-event]
     (let [state' (or state (initial-state))]
       (case (:type incoming-event)
         nil
         [state' {:wait-on (board-event-types)}]

         :game-of-life/cell-clicked
         (let [coords (cell-coords incoming-event)
               next-live-cells (if (contains? (:live-cells state') coords)
                                 (disj (:live-cells state') coords)
                                 (conj (:live-cells state') coords))
               next-state (assoc state' :live-cells next-live-cells)]
           [next-state
            {:wait-on (board-event-types)
             :request [(board-changed-event height width state' next-state)]}])

         :game-of-life/reset-clicked
         (let [next-state (initial-state)]
           [next-state
            {:wait-on (board-event-types)
             :request [(board-changed-event height width state' next-state)]}])

         :game-of-life/advance-generation
         (let [next-state {:live-cells (evolve-live-cells height width (:live-cells state'))
                           :generation (inc (:generation state'))}]
           [next-state
            {:wait-on (board-event-types)
             :request [(board-changed-event height width state' next-state)]}])

         :game-of-life/get-board-snapshot
         [state'
          {:wait-on (board-event-types)
           :request [(board-snapshot-event height width state' (:client-id incoming-event))]}]

         [state' {:wait-on (board-event-types)}])))))
