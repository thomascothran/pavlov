(ns pavlov-web-example.browser-only.routes
  (:require [pavlov-web-example.browser-only.handlers :as handlers]
            [pavlov-web-example.browser-only.websocket :as websocket]))

(defn get-routes
  []
  [["/browser-only"
    {:get (fn [req]
            (handlers/browser-only-shell req))}]
   ["/browser-only/ws/"
    {:get websocket/handler}]])
