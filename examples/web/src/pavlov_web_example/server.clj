(ns pavlov-web-example.server
  (:require [muuntaja.core :as m]
            [pavlov-web-example.routes :as routes]
            [reitit.ring :as ring]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]))

(def resource-handler-options
  {:path "/"
   :root "public"})

(defn- middleware []
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   exception/exception-middleware
   muuntaja/format-request-middleware
   multipart/multipart-middleware])

(defn make-handler
  []
  (ring/ring-handler
   (ring/router
    (routes/app-routes)
    {:data {:muuntaja m/instance
            :middleware (middleware)}})
   (ring/routes
    (ring/create-resource-handler resource-handler-options)
    (ring/create-default-handler))))

(defn app-handler
  ([]
   (app-handler {}))
  ([{:keys [hot-reload]}]
   (let [stable-handler (make-handler)]
     (if hot-reload
       (fn [req]
         ((make-handler) req))
       stable-handler))))
