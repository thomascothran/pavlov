(ns pavlov-web-example.app
  (:require [pavlov-web-example.server :as server]
            [ring.adapter.jetty9 :as jetty]))

(defonce !server
  (atom nil))

(def default-port 4980)

(defn- parse-port [value]
  (some-> value Integer/parseInt))

(defn- resolve-port [port]
  (or port
      (parse-port (System/getenv "PORT"))
      default-port))

(defn stop! []
  (when-let [server @!server]
    (.stop server)
    (reset! !server nil))
  :stopped)

(defn start!
  ([]
   (start! {}))
  ([{:keys [port hot-reload]
     :as _options}]
   (stop!)
   (reset! !server
           (jetty/run-jetty (server/app-handler {:hot-reload hot-reload})
                            {:port (resolve-port port)
                             :join? false}))))

(defn -main
  [& [port]]
  (start! {:port (parse-port port)})
  @(promise))
