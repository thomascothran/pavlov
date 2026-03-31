(ns pavlov-web-example.browser-only.websocket
  (:require [clojure.edn :as edn]
            [ring.websocket]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.web.server :as server]
            [tech.thomascothran.pavlov.web.server.ring-websocket :as ring-websocket]))

(def ^:private initialize-button-text-event
  {:type :pavlov.web.dom/op
   :selector "#browser-only-initialize-button"
   :kind :set
   :member "textContent"
   :value "initialized"})

(defn- unwrap-server-event [event]
  (if (= :pavlov.web.server/event-received (:type event))
    (:event event)
    event))

(defn make-bthreads []
  {:initialize-clicked->send-event
   (b/on-any #{:browser-only/initialize-clicked
               :pavlov.web.server/event-received}
             (fn [event]
               (when (= :browser-only/initialize-clicked
                        (:type (unwrap-server-event event)))
                 {:request #{{:type :pavlov.web.server/send-event
                              :event initialize-button-text-event}}})))} )

(defn handler
  [_request]
  (let [backend-bthreads (vals (make-bthreads))
        bridge (atom nil)
        submit-event! (fn [event]
                        (when-let [bridge-bthread @bridge]
                          (b/notify! bridge-bthread event))
                        (doseq [backend-bthread backend-bthreads]
                          (when-let [requested-events (:request (b/notify! backend-bthread event))]
                            (doseq [requested-event requested-events]
                              (when-let [bridge-bthread @bridge]
                                (b/notify! bridge-bthread requested-event))))))
        ring-adapter (ring-websocket/make-ring-websocket-adapter
                      {:submit-event! submit-event!
                       :send-websocket! (fn [websocket payload]
                                          (ring.websocket/send websocket payload))
                       :encode pr-str
                       :decode edn/read-string})
        listener (:listener ring-adapter)]
    (reset! bridge (server/make-server-bridge-bthread submit-event!
                                                      (:bridge-opts ring-adapter)))
    (b/notify! @bridge nil)
    (doseq [backend-bthread backend-bthreads]
      (b/notify! backend-bthread nil))
    {:ring.websocket/listener
     (assoc listener
            :on-close
            (fn [websocket status-code reason]
              ((:on-close listener) websocket status-code reason)))}))
