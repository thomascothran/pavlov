(ns pavlov-web-example.game-of-life.dom-ops
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn- dom-event-types
  []
  #{:game-of-life/board-changed
    :game-of-life/board-snapshot
    :game-of-life/status-changed
    :game-of-life/status-snapshot})

(defn- cell-selector
  [[row col]]
  (str "[data-game-of-life-cell][data-row=\"" row "\"][data-col=\"" col "\"]"))

(defn- status-dom-ops
  [{:keys [running? generation live-count]}]
  [{:type :pavlov.web.dom/op
    :selector "[data-game-of-life-status]"
    :kind :set
    :member "textContent"
    :value (str (if running? "Running" "Paused")
                " · Generation " generation
                " · " live-count " live cell"
                (when (not= 1 live-count) "s"))}
   {:type :pavlov.web.dom/op
    :selector "[data-game-of-life-status]"
    :kind :set
    :member "className"
    :value (str "game-of-life-status "
                (if running?
                  "game-of-life-status--running"
                  "game-of-life-status--paused"))}
   {:type :pavlov.web.dom/op
    :selector "#game-of-life-start-button"
    :kind :set
    :member "disabled"
    :value running?}
   {:type :pavlov.web.dom/op
    :selector "#game-of-life-pause-button"
    :kind :set
    :member "disabled"
    :value (not running?)}])

(defn- cell-dom-ops
  [coords alive?]
  [{:type :pavlov.web.dom/op
    :selector (cell-selector coords)
    :kind :set
    :member "className"
    :value (str "game-of-life-cell "
                (if alive?
                  "game-of-life-cell--alive"
                  "game-of-life-cell--dead"))}
   {:type :pavlov.web.dom/op
    :selector (cell-selector coords)
    :kind :call
    :member "setAttribute"
    :args ["data-cell-state" (if alive? "alive" "dead")]}
   {:type :pavlov.web.dom/op
    :selector (cell-selector coords)
    :kind :set
    :member "textContent"
    :value (if alive? "O" ".")}])

(defn- board-changed-dom-ops
  [{:keys [created-cells killed-cells] :as status-state}]
  (vec (concat
        (mapcat #(cell-dom-ops % true) created-cells)
        (mapcat #(cell-dom-ops % false) killed-cells)
        (status-dom-ops status-state))))

(defn- all-coords
  [height width]
  (for [row (map str (range height))
        col (map str (range width))]
    [row col]))

(defn- board-snapshot-dom-ops
  [{:keys [height width live-cells] :as status-state}]
  (vec (concat
        (status-dom-ops status-state)
        (mapcat #(cell-dom-ops % (contains? live-cells %))
                (all-coords height width)))))

(defn- broadcast-event
  [ops]
  {:type :game-of-life/broadcast
   :event {:type :pavlov.web.dom/ops
           :ops ops}})

(defn- send-to-client-event
  [client-id ops]
  {:type :game-of-life/send-to-client
   :client-id client-id
   :event {:type :pavlov.web.dom/ops
           :ops ops}})

(defn make-dom-ops-bthread
  []
  (b/step
   (fn [state incoming-event]
     (let [state' (merge {:running? false
                          :generation 0
                          :live-count 0}
                         state)]
       (case (:type incoming-event)
         nil
         [state' {:wait-on (dom-event-types)}]

         :game-of-life/board-changed
         (let [next-state (assoc state'
                                 :generation (:generation incoming-event)
                                 :live-count (:live-count incoming-event))]
           [next-state
            {:wait-on (dom-event-types)
             :request [(broadcast-event
                        (board-changed-dom-ops (merge next-state incoming-event)))]}])

         :game-of-life/board-snapshot
         (let [next-state (assoc state'
                                 :generation (:generation incoming-event)
                                 :live-count (:live-count incoming-event))]
           [next-state
            {:wait-on (dom-event-types)
             :request [(send-to-client-event
                        (:client-id incoming-event)
                        (board-snapshot-dom-ops (merge next-state incoming-event)))]}])

         :game-of-life/status-changed
         (let [next-state (assoc state' :running? (:running? incoming-event))]
           [next-state
            {:wait-on (dom-event-types)
             :request [(broadcast-event (vec (status-dom-ops next-state)))]}])

         :game-of-life/status-snapshot
         (let [next-state (assoc state' :running? (:running? incoming-event))]
           [next-state
            {:wait-on (dom-event-types)
             :request [(send-to-client-event
                        (:client-id incoming-event)
                        (vec (status-dom-ops next-state)))]}])

         [state' {:wait-on (dom-event-types)}])))))
