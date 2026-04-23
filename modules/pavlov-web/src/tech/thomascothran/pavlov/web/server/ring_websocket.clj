(ns tech.thomascothran.pavlov.web.server.ring-websocket
  (:require [tech.thomascothran.pavlov.web.server :as server]))

(def ^:private shared-bridge-opt-keys
  [:connect! :send! :close! :encode :decode :!websocket])

(defn- make-bridge-opts
  "Returns only the shared bridge options exposed past the Ring adapter seam."
  [opts bridge-send!]
  (cond-> (select-keys opts shared-bridge-opt-keys)
    bridge-send! (assoc :send! bridge-send!)))

(defn make-ring-websocket-adapter
  "Builds a thin Ring websocket adapter around the shared server bridge seam.

  Ring owns websocket lifecycle delivery, so this adapter keeps listener wiring
  local: it captures the runtime websocket handle, maintains `:!websocket`, and
  submits the shared Pavlov bridge events directly."
  [opts]
  (let [!websocket (atom nil)
        submit-event! (get opts :submit-event!)
        _ (assert (fn? submit-event!))
        decode (get opts :decode identity)
        send-websocket! (get opts :send-websocket!)
        _ (assert (fn? send-websocket!))
        on-error! (or (:on-error! opts) (constantly :error))
        bridge-send! (or (:send! opts)
                         (when send-websocket!
                           (fn
                             ([payload]
                              (when-let [websocket @!websocket]
                                (send-websocket! websocket payload)))
                             ([websocket payload]
                              (send-websocket! websocket payload)))))]
    {:listener {:on-open (fn [websocket]
                           (reset! !websocket websocket)
                           (submit-event! {:type :pavlov.web.server/connected}))
                 :on-message (fn [_websocket raw-payload]
                               (let [event (decode raw-payload)]
                                 (when-not (= server/heartbeat-type (:type event))
                                   (submit-event! {:type :pavlov.web.server/event-received
                                                   :event event}))))
                :on-close (fn [_websocket _status-code _reason]
                            (reset! !websocket nil)
                            (submit-event! {:type :pavlov.web.server/disconnected}))
                :on-error (fn [websocket error]
                            (on-error! websocket error))}
     :bridge-opts (make-bridge-opts (assoc opts :!websocket !websocket)
                                    bridge-send!)}))
