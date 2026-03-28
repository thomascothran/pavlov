(ns pavlov-web-example.client.main
  (:require [pavlov-web-example.browser-only.client :as browser-only]))

(def pages
  [browser-only/page])

(defn init! []
  (when-let [page (some (fn [{:keys [mounted?] :as page}]
                          (when (mounted?)
                            page))
                        pages)]
    ((:init! page))))
