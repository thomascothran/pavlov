(ns tech.thomascothran.pavlov.web.server.websocket
  (:require [tech.thomascothran.pavlov.web.server :as server]))

(defn- log
  [& args]
  (.log js/console (apply str "[pavlov.web.websocket] " args)))

(defn- warn
  [& args]
  (.warn js/console (apply str "[pavlov.web.websocket] " args)))

(defn- error
  [& args]
  (.error js/console (apply str "[pavlov.web.websocket] " args)))

(defn- submit-connected! [submit-event!]
  (submit-event! {:type :pavlov.web.server/connected}))

(defn- submit-disconnected! [submit-event!]
  (submit-event! {:type :pavlov.web.server/disconnected}))

(defn- submit-received! [submit-event! decode raw-payload]
  (let [event (decode raw-payload)]
    (when-not (= server/heartbeat-type (:type event))
      (submit-event! {:type :pavlov.web.server/event-received
                      :event event}))))

(defn- default-websocket-factory
  ([url]
   (js/WebSocket. url))
  ([url protocols]
   (js/WebSocket. url protocols)))

(defn make-browser-websocket-transport
  "Builds the browser-specific transport map consumed by the generic server
  bridge.

  This function exists to isolate browser `js/WebSocket` setup behind the
  transport callbacks expected by the transport-agnostic bridge bthread, so the
  bridge can model connection lifecycle and message flow without depending on
  websocket APIs directly. Without this adapter, websocket wiring would leak
  into generic bridge code or application bthreads, making the bridge harder to
  test without a live browser websocket.

  Accepts an option map with:
  - `:url` websocket URL passed to the socket factory.
  - `:protocols` optional websocket subprotocols passed to the socket factory.
  - `:websocket` existing socket value to seed internal state, primarily for
    controlled setup.
  - `:websocket-factory` function of `[url protocols]` used to create the
    browser websocket, defaulting to `js/WebSocket`.
  - `:encode` outbound payload encoder stored on the returned transport map.
  - `:decode` inbound payload decoder stored on the returned transport map.

  The returned map provides `:connect!`, `:send!`, `:close!`, `:encode`, and
  `:decode`. `:connect!` creates the websocket and wires its open, message, and
  close callbacks to the generic bridge lifecycle hooks; `:send!` forwards
  outbound payloads through the created websocket.

  Example:
  `(make-browser-websocket-transport
     {:url \"ws://localhost:9000/ws\"
      :protocols #js [\"pavlov\"]
      :encode js/JSON.stringify
      :decode js/JSON.parse})`"
  [{:keys [url protocols websocket !websocket submit-event! websocket-factory encode decode]
       :or {encode identity
            decode identity
            submit-event! (fn [_] nil)
            websocket-factory default-websocket-factory}}]
  (let [!socket (or !websocket (atom websocket))
        !last-closed-socket (atom nil)
        !generation (atom 0)
        open-ready-state 1
        socket-open? (fn [socket]
                        (or (nil? (.-readyState socket))
                            (= open-ready-state (.-readyState socket))))
        disconnect-current! (fn [socket]
                              (when (and socket (identical? socket @!socket))
                                (reset! !socket nil)
                                (submit-disconnected! submit-event!)))]
    {:connect! (fn []
                  (log "connect! url=" url
                       (when (some? protocols)
                         (str " protocols=" protocols)))
                  (let [generation (swap! !generation inc)
                        current-generation? #(= generation @!generation)
                        socket (if (some? protocols)
                                  (websocket-factory url protocols)
                                  (try
                                    (websocket-factory url)
                                    (catch :default _
                                     (websocket-factory url protocols))))]
                      (set! (.-onopen socket)
                            (fn [& _]
                              (log "onopen readyState=" (.-readyState socket))
                              (when (current-generation?)
                                (reset! !socket socket)
                                (submit-connected! submit-event!))))
                      (set! (.-onmessage socket)
                            (fn [event]
                              (when (current-generation?)
                                (let [payload (.-data event)]
                                  (log "onmessage raw=" (pr-str payload))
                                  (submit-received! submit-event! decode payload)))))
                      (set! (.-onerror socket)
                            (fn [event]
                              (when (current-generation?)
                                (error "onerror readyState=" (.-readyState socket)
                                       " event=" (pr-str event))
                                (disconnect-current! socket))))
                      (set! (.-onclose socket)
                            (fn [event]
                              (when (and (current-generation?)
                                         (identical? socket @!socket))
                                 (warn "onclose readyState=" (.-readyState socket)
                                       " code=" (some-> event .-code)
                                       " reason=" (pr-str (some-> event .-reason))
                                       " wasClean=" (some-> event .-wasClean))
                                 (reset! !last-closed-socket socket)
                                 (reset! !socket nil)
                                 (submit-disconnected! submit-event!))))
                      socket))
       :send! (fn
                 ([payload]
                  (log "send! readyState=" (some-> @!socket .-readyState)
                       " payload=" (pr-str payload))
                  (let [socket @!socket]
                    (when-not (and socket (socket-open? socket))
                      (disconnect-current! socket)
                      (throw (js/Error. "Cannot send websocket payload: socket is not open")))
                    (try
                      (.send socket payload)
                      (catch :default error
                        (disconnect-current! socket)
                        (throw error)))))
                 ([socket payload]
                  (log "send! explicit-socket readyState=" (.-readyState socket)
                       " payload=" (pr-str payload))
                  (when-not (and socket (socket-open? socket))
                    (disconnect-current! socket)
                    (throw (js/Error. "Cannot send websocket payload: socket is not open")))
                  (try
                    (.send socket payload)
                    (catch :default error
                      (disconnect-current! socket)
                      (throw error)))))
        :close! (fn []
                  (when-let [socket (or @!socket @!last-closed-socket)]
                    (swap! !generation inc)
                    (reset! !socket nil)
                    (reset! !last-closed-socket nil)
                    (.close socket)))
       :encode encode
       :!websocket !socket
      :decode decode}))
