(ns tech.thomascothran.pavlov.web.server.websocket)

(defn- default-websocket-factory [url protocols]
  (js/WebSocket. url protocols))

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
  [{:keys [url protocols websocket websocket-factory encode decode]
     :or {encode identity
          decode identity
          websocket-factory default-websocket-factory}}]
  (let [!socket (atom websocket)]
    {:connect! (fn [{:keys [on-connected on-message on-disconnected]}]
                 (let [socket (websocket-factory url protocols)]
                   (reset! !socket socket)
                   (set! (.-onopen socket)
                         (fn [& _]
                           (on-connected)))
                   (set! (.-onmessage socket)
                         (fn [event]
                           (on-message (.-data event))))
                   (set! (.-onclose socket)
                         (fn [& _]
                           (on-disconnected)))
                   socket))
     :send! (fn [payload]
              (.send @!socket payload))
    :close! (fn [] nil)
    :encode encode
     :decode decode}))
