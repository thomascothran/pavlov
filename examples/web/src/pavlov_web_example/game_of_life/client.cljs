(ns pavlov-web-example.game-of-life.client
  (:require [pavlov-web-example.client.runtime :as runtime]))

(def page-id :game-of-life)

(defn init! []
  (runtime/init! {:ws-path "/game-of-life/ws/"
                  :heartbeat-interval-ms 20000
                  :make-program (fn [{:keys [query-selector submit! transport bridge-bthread]}]
                                  (runtime/make-bridged-program!
                                   {:query-selector query-selector
                                    :submit! submit!
                                    :transport transport
                                    :bridge-bthread bridge-bthread
                                    :forwarded-events #{:game-of-life/cell-clicked
                                                        :game-of-life/start-clicked
                                                        :game-of-life/pause-clicked
                                                        :game-of-life/reset-clicked}
                                    :forwarded-event->server-event
                                    (fn [event]
                                      (select-keys event [:type :pavlov-row :pavlov-col]))
                                    :page-bthreads []}))}))

(defn mounted?
  []
  (= "game-of-life"
     (.getAttribute (.-body js/document) "data-pavlov-page")))

(def page
  {:page-id page-id
   :mounted? mounted?
   :init! init!})
