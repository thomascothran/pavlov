(ns pavlov-web-example.client.main
  (:require [pavlov-web-example.browser-only.client :as browser-only]
            [pavlov-web-example.game-of-life.client :as game-of-life]))

(def pages
  [browser-only/page
   game-of-life/page])

(defn init-mounted-page!
  "Initialize the first mounted page from `candidate-pages`."
  [candidate-pages]
  (when-let [page (some (fn [{:keys [mounted?] :as page}]
                          (when (mounted?)
                            page))
                        candidate-pages)]
    ((:init! page))))

(defn init!
  ([]
   (init-mounted-page! pages))
  ([candidate-pages]
   (init-mounted-page! candidate-pages)))
