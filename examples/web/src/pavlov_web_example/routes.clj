(ns pavlov-web-example.routes
  (:require [pavlov-web-example.browser-only.routes :as browser-only-routes]))

(defn app-routes
  []
  (browser-only-routes/get-routes))
