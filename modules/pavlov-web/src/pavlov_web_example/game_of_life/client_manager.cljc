(ns pavlov-web-example.game-of-life.client-manager
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn- event-types
  []
  #{:game-of-life/client-opened
    :game-of-life/client-closed
    :game-of-life/status-snapshot})

(defn make-client-manager-bthread
  []
  (b/step
   (fn [state incoming-event]
     (let [state' (or state {})]
       (case (:type incoming-event)
         nil
         [state' {:wait-on (event-types)}]

         :game-of-life/client-opened
         [state'
          {:wait-on (event-types)
           :request [{:type :game-of-life/get-status-snapshot
                      :client-id (:client-id incoming-event)}]}]

         :game-of-life/status-snapshot
         [state'
          {:wait-on (event-types)
           :request [{:type :game-of-life/get-board-snapshot
                      :client-id (:client-id incoming-event)}]}]

         [state' {:wait-on (event-types)}])))))
