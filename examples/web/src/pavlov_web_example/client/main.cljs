(ns pavlov-web-example.client.main
  (:require [pavlov-web-example.browser-only.client :as browser-only]
            [pavlov-web-example.game-of-life.client :as game-of-life]))

(def pages
  [browser-only/page
   game-of-life/page])

(defn init! []
  (when-let [page (some (fn [{:keys [mounted?] :as page}]
                          (when (mounted?)
                            page))
                        pages)]
    ((:init! page))))
