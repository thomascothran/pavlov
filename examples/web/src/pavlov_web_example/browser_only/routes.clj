(ns pavlov-web-example.browser-only.routes
  (:require [pavlov-web-example.browser-only.handlers :as handlers]))

(defn get-routes
  []
  [["/"
    {:get (fn [req]
            (handlers/browser-only-shell req))}]])
