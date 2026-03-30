(ns pavlov-web-example.routes
  (:require [pavlov-web-example.browser-only.routes :as browser-only-routes]
            [pavlov-web-example.game-of-life.routes :as game-of-life-routes]))

(defn app-routes
  []
  (into []
        (concat (browser-only-routes/get-routes)
                (game-of-life-routes/get-routes))))
