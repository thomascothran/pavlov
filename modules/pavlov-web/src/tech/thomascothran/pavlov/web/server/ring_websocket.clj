(ns tech.thomascothran.pavlov.web.server.ring-websocket
  (:require [tech.thomascothran.pavlov.web.server :as server]))

(defn- noop
  [& _])

(def ^:private shared-bridge-opt-keys
  [:connect! :send! :_close! :encode :decode])

(defn- make-bridge-opts
  "Returns only the shared bridge options exposed past the Ring adapter seam."
  [opts bridge-send!]
  (cond-> (select-keys opts shared-bridge-opt-keys)
    bridge-send! (assoc :send! bridge-send!)))

(defn make-ring-websocket-adapter
  "Builds a thin Ring websocket adapter around the shared server bridge callbacks.

  Ring owns websocket lifecycle delivery, so this adapter captures the runtime
  websocket handle on open and delegates the shared bridge event mapping to
  `server/make-server-bridge-callbacks` instead of re-encoding it locally."
  [opts]
  (let [!websocket (atom nil)
        submit-event! (or (:submit-event! opts) noop)
        bridge-callbacks (server/make-server-bridge-callbacks submit-event!
                                                              (or (:decode opts)
                                                                  identity))
        send-websocket! (:send-websocket! opts)
        on-error! (or (:on-error! opts) noop)
        bridge-send! (or (:send! opts)
                         (when send-websocket!
                           (fn [payload]
                             (when-let [websocket @!websocket]
                               (send-websocket! websocket payload)))))]
    {:listener {:on-open (fn [websocket]
                           (reset! !websocket websocket)
                           ((:on-connected bridge-callbacks) websocket))
                :on-message (fn [_websocket raw-payload]
                              ((:on-message bridge-callbacks) raw-payload))
                :on-close (fn [_websocket _status-code _reason]
                            (reset! !websocket nil)
                            ((:on-disconnected bridge-callbacks)))
                :on-error (fn [websocket error]
                            (on-error! websocket error))}
     :bridge-opts (make-bridge-opts opts bridge-send!)}))
