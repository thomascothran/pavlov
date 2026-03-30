(ns pavlov-web-example.game-of-life.game-status
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn- status-event-types
  []
  #{:game-of-life/start-clicked
    :game-of-life/pause-clicked
    :game-of-life/reset-clicked
    :game-of-life/tick
    :game-of-life/get-status-snapshot})

(defn- emit-status-change
  [running?]
  {:type :game-of-life/status-changed
   :running? running?})

(defn- emit-status-snapshot
  [running? client-id]
  {:type :game-of-life/status-snapshot
   :client-id client-id
   :running? running?})

(defn make-game-status-bthread
  []
  (b/step
   (fn [state incoming-event]
     (let [state' (or state {:running? false})]
       (case (:type incoming-event)
         nil
         [state' {:wait-on (status-event-types)}]

         :game-of-life/start-clicked
         (let [next-state {:running? true}]
           [next-state
            (cond-> {:wait-on (status-event-types)}
              (not (:running? state'))
              (assoc :request [(emit-status-change true)]))])

         :game-of-life/pause-clicked
         (let [next-state {:running? false}]
           [next-state
            (cond-> {:wait-on (status-event-types)}
              (:running? state')
              (assoc :request [(emit-status-change false)]))])

         :game-of-life/reset-clicked
         (let [next-state {:running? false}]
           [next-state
            (cond-> {:wait-on (status-event-types)}
              (:running? state')
              (assoc :request [(emit-status-change false)]))])

         :game-of-life/tick
         [state'
          (cond-> {:wait-on (status-event-types)}
            (:running? state')
            (assoc :request [{:type :game-of-life/advance-generation}]))]

         :game-of-life/get-status-snapshot
         [state'
          {:wait-on (status-event-types)
            :request [(emit-status-snapshot (:running? state')
                                            (:client-id incoming-event))]}]

         [state' {:wait-on (status-event-types)}])))))
