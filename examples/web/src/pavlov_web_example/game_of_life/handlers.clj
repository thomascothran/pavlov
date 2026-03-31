(ns pavlov-web-example.game-of-life.handlers
  (:require [pavlov-web-example.game-of-life.config :as config]
            [pavlov-web-example.game-of-life.page :as page]))

(defn game-of-life-shell
  [_]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body (page/render-page {:height config/board-height
                            :width config/board-width})})
