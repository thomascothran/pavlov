(ns pavlov-web-example.game-of-life.routes
  (:require [pavlov-web-example.game-of-life.handlers :as handlers]
            [pavlov-web-example.game-of-life.websocket :as websocket]))

(defn get-routes
  []
  [["/game-of-life"
    {:get (fn [req]
            (handlers/game-of-life-shell req))}]
   ["/game-of-life/ws/"
    {:get websocket/handler}]])
