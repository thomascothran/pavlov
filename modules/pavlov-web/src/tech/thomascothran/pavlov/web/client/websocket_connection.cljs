(ns tech.thomascothran.pavlov.web.client.websocket-connection
  (:require [tech.thomascothran.pavlov.web.server :as server]
            [tech.thomascothran.pavlov.web.server.websocket :as websocket]))

(defn- websocket-url
  [ws-path]
  (if (exists? js/window)
    (let [location (.-location js/window)
          protocol (if (= "https:" (.-protocol location))
                     "wss:"
                     "ws:")]
      (str protocol "//" (.-host location) ws-path))
    ws-path))

(defn- make-default-browser-transport
  [opts]
  (when (fn? js/WebSocket)
    (websocket/make-browser-websocket-transport opts)))

(defn- clear-timer!
  [!timer-id clear!]
  (when-let [timer-id @!timer-id]
    (clear! timer-id)
    (reset! !timer-id nil)))

(defn- make-heartbeat-manager!
  "Creates heartbeat controls for a single browser websocket connection.

  The manager starts heartbeat sends only after the transport reports a connected
  lifecycle event. Heartbeats are reserved bridge payloads and are intentionally
  contained here so browser liveness policy stays outside the shared bridge and
  raw websocket transport."
  [{:keys [encode heartbeat-interval-ms set-interval! clear-interval!]}
   !transport]
  (let [!interval-id (atom nil)
        clear! (fn []
                 (clear-timer! !interval-id clear-interval!))]
    {:clear! clear!
     :start! (fn []
               (when (and heartbeat-interval-ms
                          (nil? @!interval-id))
                 (reset! !interval-id
                         (set-interval!
                          (fn []
                            (try
                              (let [transport @!transport
                                    encode! (or (:encode transport) encode identity)]
                                ((:send! transport)
                                 (encode! {:type server/heartbeat-type})))
                              (catch :default _ nil)))
                          heartbeat-interval-ms))))}))

(defn- reconnect-delay-ms
  [reconnect-delays-ms attempt]
  (or (nth reconnect-delays-ms attempt nil)
      (last reconnect-delays-ms)))

(defn- make-reconnect-manager!
  "Creates reconnect controls for transport lifecycle events.

  Reconnect policy is driven by browser-client lifecycle events, not by the
  transport or bridge. Duplicate timers are prevented, attempts are reset after a
  successful connection, and cleanup can make all future reconnect work inert."
  [{:keys [reconnect-delays-ms set-timeout! clear-timeout!]}
   !transport
   !cleaned?]
  (let [!timer-id (atom nil)
        !attempt (atom 0)
        clear! (fn []
                 (clear-timer! !timer-id clear-timeout!))]
    {:clear! clear!
     :connected! (fn []
                   (reset! !attempt 0)
                   (clear!))
     :disconnected! (fn []
                      (when (and (not @!cleaned?)
                                 (nil? @!timer-id))
                        (let [attempt @!attempt
                              delay-ms (reconnect-delay-ms reconnect-delays-ms attempt)]
                          (swap! !attempt inc)
                          (reset! !timer-id
                                  (set-timeout!
                                   (fn []
                                     (reset! !timer-id nil)
                                     (when-not @!cleaned?
                                       ((:connect! @!transport))))
                                   delay-ms)))))}))

(defn- submit-with-lifecycle!
  [submit! heartbeat reconnect event]
  (submit! event)
  (case (:type event)
    :pavlov.web.server/connected
    (do
      ((:connected! reconnect))
      ((:start! heartbeat)))

    :pavlov.web.server/disconnected
    (do
      ((:clear! heartbeat))
      ((:disconnected! reconnect)))

    nil))

(defn make-browser-websocket-connection!
  "Create reusable browser websocket bridge wiring and lifecycle controls.

  This browser-client manager centralizes host orchestration that every
  websocket-backed browser runtime would otherwise need to duplicate: delayed
  reconnects, heartbeat liveness, cleanup, and pagehide teardown. Keeping those
  responsibilities here lets app runtimes compose pages and bthreads while the
  shared bridge remains transport-agnostic and the websocket transport remains
  focused on socket mechanics.

  Required config:
  - `:ws-path` is the websocket path, resolved against `js/window.location` when
    available.

  Optional seams:
  - `:submit!` receives transport lifecycle/domain events before manager policy
    reacts; defaults to an inert function.
  - `:make-transport`, `:encode`, and `:decode` configure the underlying browser
    websocket transport.
  - `:reconnect-delays-ms` and `:heartbeat-interval-ms` configure connection
    policy.
  - timer and `:add-pagehide-listener!` functions are injectable for deterministic
    runtimes and tests.

  Returns `{:transport ... :bridge-bthread ... :start! ... :cleanup! ...}`.
  Construction does not connect; call `:start!` to connect once, and call
  `:cleanup!` to close the transport and cancel reconnect/heartbeat work.

  Example:
  ```clojure
  (let [{:keys [bridge-bthread start! cleanup!]}
        (make-browser-websocket-connection!
          {:ws-path \"/ws\"
           :submit! submit-to-program!
           :encode encode-event
           :decode decode-event
           :heartbeat-interval-ms 30000})]
    ;; install bridge-bthread in the browser program, then:
    (start!)
    cleanup!)
  ```"
  [{:keys [ws-path submit! make-transport encode decode reconnect-delays-ms heartbeat-interval-ms set-timeout! clear-timeout! set-interval! clear-interval! add-pagehide-listener!]
    :or {submit! (fn [_] nil)
         make-transport make-default-browser-transport
         reconnect-delays-ms [250 1000 5000 10000]
         set-timeout! js/setTimeout
         clear-timeout! js/clearTimeout
         set-interval! js/setInterval
         clear-interval! js/clearInterval
         add-pagehide-listener! (fn [handler]
                                  (when (and (exists? js/window)
                                             (.-addEventListener js/window))
                                    (.addEventListener js/window "pagehide" handler)))}}]
  (let [!transport (atom nil)
        !cleaned? (atom false)
        heartbeat (make-heartbeat-manager! {:encode encode
                                            :heartbeat-interval-ms heartbeat-interval-ms
                                            :set-interval! set-interval!
                                            :clear-interval! clear-interval!}
                                           !transport)
        reconnect (make-reconnect-manager! {:reconnect-delays-ms reconnect-delays-ms
                                            :set-timeout! set-timeout!
                                            :clear-timeout! clear-timeout!}
                                           !transport
                                           !cleaned?)
        cleanup! (fn []
                   (when (compare-and-set! !cleaned? false true)
                     ((:clear! reconnect))
                     ((:clear! heartbeat))
                     (when-let [transport @!transport]
                       ((:close! transport)))))
        managed-submit! (fn [event]
                          (submit-with-lifecycle! submit! heartbeat reconnect event))
        transport (make-transport {:url (websocket-url ws-path)
                                   :ws-path ws-path
                                   :submit! managed-submit!
                                   :submit-event! managed-submit!
                                   :encode encode
                                   :decode decode})
        !started? (atom false)]
    (reset! !transport transport)
    (add-pagehide-listener! cleanup!)
    {:transport transport
     :bridge-bthread (when transport
                       (server/make-server-bridge-bthread submit! transport))
     :start! (fn []
               (when (and (not @!cleaned?)
                          transport
                          (compare-and-set! !started? false true))
                 ((:connect! transport))))
     :cleanup! cleanup!}))
