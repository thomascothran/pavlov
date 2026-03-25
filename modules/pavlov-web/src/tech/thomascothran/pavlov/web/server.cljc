(ns tech.thomascothran.pavlov.web.server
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as event]))

(def ^:private disconnected-bid
  {:wait-on #{:pavlov.web.server/connected}
   :block #{:pavlov.web.server/send-event}})

(def ^:private connected-bid
  {:wait-on #{:pavlov.web.server/send-event
              :pavlov.web.server/disconnected}})

(defn- transition-to-disconnected []
  [:disconnected disconnected-bid])

(defn- transition-to-connected []
  [:connected connected-bid])

(defn- transition-to-connected-with-error-request
  [incoming-event error]
  [:connected
   (assoc connected-bid
          :request
          #{{:type (:on-error-event-type incoming-event)
             :event (:event incoming-event)
             :error {:message (ex-message error)}}})])

(defn- handle-outbound-send! [encode send! incoming-event]
  (try
    (send! (encode (:event incoming-event)))
    (transition-to-connected)
    (catch #?(:clj Throwable :cljs :default) error
      (if-let [_ (:on-error-event-type incoming-event)]
        (transition-to-connected-with-error-request incoming-event error)
        (transition-to-connected)))))

(defn- disconnected-step [incoming-event]
  (if (= :pavlov.web.server/connected
         (some-> incoming-event event/type))
    (transition-to-connected)
    (transition-to-disconnected)))

(defn- connected-step [encode send! incoming-event]
  (case (some-> incoming-event event/type)
    :pavlov.web.server/disconnected
    (transition-to-disconnected)

    :pavlov.web.server/send-event
    (handle-outbound-send! encode send! incoming-event)

    (transition-to-connected)))

(defn- transport-callbacks [submit-event! decode]
  {:on-connected (fn [& _]
                   (submit-event! {:type :pavlov.web.server/connected}))
   :on-disconnected (fn [& _]
                      (submit-event! {:type :pavlov.web.server/disconnected}))
   :on-message (fn [raw-payload]
                 (submit-event! {:type :pavlov.web.server/event-received
                                 :event (decode raw-payload)}))})

(defn- initialize-bridge! [submit-event! connect! decode]
  (connect! (transport-callbacks submit-event! decode))
  (transition-to-disconnected))

(defn make-server-bridge-bthread
  "Creates a transport-agnostic bridge bthread for one remote connection.

  This function exists so application bthreads can interact with connection
  lifecycle, outbound sends, and inbound receipts through Pavlov events instead
  of calling transport APIs directly. Without this bridge, transport details
  would leak into behavioral logic, making the main bridge behavior harder to
  test without a live websocket and harder to reuse with fake transports.

  `submit-event!` is the callback the bridge uses to feed transport lifecycle
  and inbound-message events back into the host program. The options map
  injects transport-specific behavior: `connect!` installs callbacks,
  `send!` delivers encoded outbound payloads, `encode` turns semantic outbound
  events into wire payloads, and `decode` turns inbound payloads into semantic
  events. `_close!` is accepted for interface compatibility even though this
  bridge does not currently call it.

  Behavior:
  - Starts disconnected, waiting on `:pavlov.web.server/connected` and blocking
    `:pavlov.web.server/send-event`
  - Calls `connect!` during initialization with callbacks that submit
    `:pavlov.web.server/connected`, `:pavlov.web.server/disconnected`, and
    decoded `:pavlov.web.server/event-received` events
  - After connection, handles `:pavlov.web.server/send-event` by encoding
    `(:event incoming-event)` and passing the payload to `send!`
  - If `encode` or `send!` throws and the incoming send event provides
    `:on-error-event-type`, requests the configured pure-data follow-up event

  Example:
  (make-server-bridge-bthread
    submit-event!
    {:connect! connect!
     :send! send!
     :encode encode
     :decode decode})"
  [submit-event! {:keys [connect! send! _close! encode decode]}]
  (b/step
   (fn [state incoming-event]
     (if (nil? state)
       (initialize-bridge! submit-event! connect! decode)
       (case state
         :disconnected
         (disconnected-step incoming-event)

         :connected
         (connected-step encode send! incoming-event))))))
