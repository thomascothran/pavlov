(ns tech.thomascothran.pavlov.web.client.websocket-connection-test
  (:require [cljs.test :refer-macros [deftest is]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.web.client.websocket-connection :as connection]
            [tech.thomascothran.pavlov.web.server :as server]))

(deftest make-browser-websocket-connection-returns-lifecycle-controls-with-explicit-idempotent-start
  (let [!connect-calls (atom 0)
        fake-transport {:connect! (fn []
                                    (swap! !connect-calls inc)
                                    :connected-socket)
                        :send! (fn [_payload] nil)
                        :close! (fn [] nil)
                        :encode identity
                        :decode identity}
        lifecycle (connection/make-browser-websocket-connection!
                   {:ws-path "/ws"
                    :submit! (fn [_event] nil)
                    :make-transport (fn [_opts]
                                      fake-transport)})]
    (is (map? lifecycle)
        "constructing a browser websocket connection should return lifecycle wiring")
    (is (identical? fake-transport (:transport lifecycle))
        "the lifecycle map should expose the constructed transport")
    (is (contains? lifecycle :bridge-bthread)
        "the lifecycle map should expose bridge bthread wiring for the runtime")
    (is (fn? (:start! lifecycle))
        "the lifecycle map should expose an explicit start! function")
    (is (fn? (:cleanup! lifecycle))
        "the lifecycle map should expose a cleanup! function")
    (is (= 0 @!connect-calls)
        "construction should not connect the browser transport")
    (when (fn? (:start! lifecycle))
      ((:start! lifecycle))
      ((:start! lifecycle)))
    (is (= 1 @!connect-calls)
        "start! should connect once and repeated start! calls should not duplicate the connection start")))

(deftest cleanup-before-explicit-start-makes-later-start-terminal
  (let [!connect-calls (atom 0)
        fake-transport {:connect! (fn []
                                    (swap! !connect-calls inc)
                                    :connected-socket)
                        :send! (fn [_payload] nil)
                        :close! (fn [] nil)
                        :encode identity
                        :decode identity}
        lifecycle (connection/make-browser-websocket-connection!
                   {:ws-path "/ws"
                    :submit! (fn [_event] nil)
                    :make-transport (fn [_opts]
                                      fake-transport)})]
    (is (fn? (:cleanup! lifecycle))
        "the lifecycle map should expose cleanup! before callers explicitly start")
    (is (fn? (:start! lifecycle))
        "the lifecycle map should expose start! for explicit connection starts")

    ((:cleanup! lifecycle))
    ((:start! lifecycle))

    (is (= 0 @!connect-calls)
        "cleanup! before explicit start should be terminal, so a later start! must not connect the transport")))

(deftest disconnected-event-schedules-one-reconnect-timeout-that-connects-transport
  (let [!transport-opts (atom nil)
        !submitted-events (atom [])
        !timeouts (atom [])
        !cleared-timeouts (atom [])
        !connect-calls (atom 0)
        fake-transport {:connect! (fn []
                                    (swap! !connect-calls inc)
                                    :connected-socket)
                        :send! (fn [_payload] nil)
                        :close! (fn [] nil)
                        :encode identity
                        :decode identity}
        lifecycle (connection/make-browser-websocket-connection!
                   {:ws-path "/ws"
                    :submit! (fn [event]
                               (swap! !submitted-events conj event))
                    :make-transport (fn [opts]
                                      (reset! !transport-opts opts)
                                      fake-transport)
                    :reconnect-delays-ms [123]
                    :set-timeout! (fn [callback delay-ms]
                                    (let [timer-id (str "timer-" (inc (count @!timeouts)))]
                                      (swap! !timeouts conj {:id timer-id
                                                             :callback callback
                                                             :delay-ms delay-ms})
                                      timer-id))
                    :clear-timeout! (fn [timer-id]
                                      (swap! !cleared-timeouts conj timer-id))})
        disconnected-event {:type :pavlov.web.server/disconnected}]
    (is (identical? fake-transport (:transport lifecycle))
        "the public API should still expose the manager-created transport")
    (is (fn? (:submit! @!transport-opts))
        "the manager should give the transport a submit function so transport lifecycle events are observable")

    ((:submit! @!transport-opts) disconnected-event)

    (is (= [disconnected-event] @!submitted-events)
        "transport lifecycle events should still be forwarded to the caller submit function")
    (is (= [123] (mapv :delay-ms @!timeouts))
        "a disconnected lifecycle event should schedule reconnect using the configured delay")

    ((:submit! @!transport-opts) disconnected-event)

    (is (= 1 (count @!timeouts))
        "additional disconnected events should not schedule duplicate reconnect timers while one is pending")
    (is (= 0 @!connect-calls)
        "scheduling reconnect should not connect until the timer callback fires")
    (is (fn? (:callback (first @!timeouts)))
        "the scheduled reconnect timer should capture a callback")

    (when-let [callback (:callback (first @!timeouts))]
      (callback))

    (is (= 1 @!connect-calls)
        "firing the reconnect timer callback should reconnect the manager-created transport")
    (is (empty? @!cleared-timeouts)
        "duplicate prevention should not require clearing the pending reconnect timer")))

(deftest disconnected-event-from-submit-event-seam-schedules-one-reconnect-timeout-that-connects-transport
  (let [!transport-opts (atom nil)
        !submitted-events (atom [])
        !timeouts (atom [])
        !cleared-timeouts (atom [])
        !connect-calls (atom 0)
        fake-transport {:connect! (fn []
                                    (swap! !connect-calls inc)
                                    :connected-socket)
                        :send! (fn [_payload] nil)
                        :close! (fn [] nil)
                        :encode identity
                        :decode identity}
        lifecycle (connection/make-browser-websocket-connection!
                   {:ws-path "/ws"
                    :submit! (fn [event]
                               (swap! !submitted-events conj event))
                    :make-transport (fn [opts]
                                      (reset! !transport-opts opts)
                                      fake-transport)
                    :reconnect-delays-ms [123]
                    :set-timeout! (fn [callback delay-ms]
                                    (let [timer-id (str "timer-" (inc (count @!timeouts)))]
                                      (swap! !timeouts conj {:id timer-id
                                                             :callback callback
                                                             :delay-ms delay-ms})
                                      timer-id))
                    :clear-timeout! (fn [timer-id]
                                      (swap! !cleared-timeouts conj timer-id))})
        disconnected-event {:type :pavlov.web.server/disconnected}]
    (is (identical? fake-transport (:transport lifecycle))
        "the public API should still expose the manager-created transport")
    (is (fn? (:submit-event! @!transport-opts))
        "the manager should give the browser transport a submit-event! function for real lifecycle events")

    ((:submit-event! @!transport-opts) disconnected-event)

    (is (= [disconnected-event] @!submitted-events)
        "transport lifecycle events from the real submit-event! seam should be forwarded to the caller submit function")
    (is (= [123] (mapv :delay-ms @!timeouts))
        "a disconnected lifecycle event from the real submit-event! seam should schedule reconnect using the configured delay")

    ((:submit-event! @!transport-opts) disconnected-event)

    (is (= 1 (count @!timeouts))
        "additional submit-event! disconnected events should not schedule duplicate reconnect timers while one is pending")
    (is (= 0 @!connect-calls)
        "scheduling reconnect from submit-event! should not connect until the timer callback fires")
    (is (fn? (:callback (first @!timeouts)))
        "the scheduled reconnect timer should capture a callback")

    (when-let [callback (:callback (first @!timeouts))]
      (callback))

    (is (= 1 @!connect-calls)
        "firing the submit-event!-scheduled reconnect timer callback should reconnect the manager-created transport")
    (is (empty? @!cleared-timeouts)
        "duplicate prevention should not require clearing the pending reconnect timer")))

(deftest connected-event-from-submit-event-seam-clears-pending-reconnect-and-resets-delay
  (let [!transport-opts (atom nil)
        !submitted-events (atom [])
        !timeouts (atom [])
        !cleared-timeouts (atom [])
        fake-transport {:connect! (fn []
                                    :connected-socket)
                        :send! (fn [_payload] nil)
                        :close! (fn [] nil)
                        :encode identity
                        :decode identity}
        _lifecycle (connection/make-browser-websocket-connection!
                    {:ws-path "/ws"
                     :submit! (fn [event]
                                (swap! !submitted-events conj event))
                     :make-transport (fn [opts]
                                       (reset! !transport-opts opts)
                                       fake-transport)
                     :reconnect-delays-ms [123 456]
                     :set-timeout! (fn [callback delay-ms]
                                     (let [timer-id (str "timer-" (inc (count @!timeouts)))]
                                       (swap! !timeouts conj {:id timer-id
                                                              :callback callback
                                                              :delay-ms delay-ms})
                                       timer-id))
                     :clear-timeout! (fn [timer-id]
                                       (swap! !cleared-timeouts conj timer-id))})
        disconnected-event {:type :pavlov.web.server/disconnected}
        connected-event {:type :pavlov.web.server/connected}]
    (is (fn? (:submit-event! @!transport-opts))
        "the manager should receive real browser transport lifecycle events through submit-event!")

    ((:submit-event! @!transport-opts) disconnected-event)
    (is (= [{:id "timer-1"
             :delay-ms 123}]
           (mapv #(select-keys % [:id :delay-ms]) @!timeouts))
        "a disconnected event should create one pending reconnect using the first configured delay")

    ((:submit-event! @!transport-opts) connected-event)
    (is (= [disconnected-event connected-event] @!submitted-events)
        "a connected transport lifecycle event should still be forwarded to the caller submit function")
    (is (= ["timer-1"] @!cleared-timeouts)
        "a connected event should clear the pending reconnect timeout")

    ((:submit-event! @!transport-opts) disconnected-event)
    (is (= [{:id "timer-1"
              :delay-ms 123}
             {:id "timer-2"
              :delay-ms 123}]
            (mapv #(select-keys % [:id :delay-ms]) @!timeouts))
         "after connected resets reconnect state, a later disconnected event should schedule a fresh reconnect with the first delay")))

(deftest failed-reconnect-attempts-advance-delay-cap-and-connected-resets-retry-state
  (let [!transport-opts (atom nil)
        !submitted-events (atom [])
        !timeouts (atom [])
        !connect-calls (atom 0)
        fake-transport {:connect! (fn []
                                    (swap! !connect-calls inc)
                                    :connected-socket)
                        :send! (fn [_payload] nil)
                        :close! (fn [] nil)
                        :encode identity
                        :decode identity}
        _lifecycle (connection/make-browser-websocket-connection!
                    {:ws-path "/ws"
                     :submit! (fn [event]
                                (swap! !submitted-events conj event))
                     :make-transport (fn [opts]
                                       (reset! !transport-opts opts)
                                       fake-transport)
                     :reconnect-delays-ms [100 200 300]
                     :set-timeout! (fn [callback delay-ms]
                                     (let [timer-id (str "timer-" (inc (count @!timeouts)))]
                                       (swap! !timeouts conj {:id timer-id
                                                              :callback callback
                                                              :delay-ms delay-ms})
                                       timer-id))
                     :clear-timeout! (fn [_timer-id] nil)})
        disconnected-event {:type :pavlov.web.server/disconnected}
        connected-event {:type :pavlov.web.server/connected}
        submit-event! (:submit-event! @!transport-opts)]
    (is (fn? submit-event!)
        "reconnect retry state should be driven by the real browser transport submit-event! seam")

    (submit-event! disconnected-event)
    (is (= [100] (mapv :delay-ms @!timeouts))
        "the first disconnect should use the first configured reconnect delay")

    ((:callback (nth @!timeouts 0)))
    (submit-event! disconnected-event)
    (is (= [100 200] (mapv :delay-ms @!timeouts))
        "a failed reconnect attempt should advance to the second configured reconnect delay")

    ((:callback (nth @!timeouts 1)))
    (submit-event! disconnected-event)
    (is (= [100 200 300] (mapv :delay-ms @!timeouts))
        "subsequent failed reconnect attempts should continue through configured delays")

    ((:callback (nth @!timeouts 2)))
    (submit-event! disconnected-event)
    (is (= [100 200 300 300] (mapv :delay-ms @!timeouts))
        "failed reconnect attempts should cap at the final configured delay")

    ((:callback (nth @!timeouts 3)))
    (submit-event! connected-event)
    (submit-event! disconnected-event)

    (is (= [100 200 300 300 100] (mapv :delay-ms @!timeouts))
        "a connected lifecycle event should reset retry state so the next disconnect uses the first delay")
    (is (= 4 @!connect-calls)
        "each fired reconnect timer should invoke transport reconnect exactly once")
    (is (= [disconnected-event disconnected-event disconnected-event disconnected-event connected-event disconnected-event]
           @!submitted-events)
        "all transport lifecycle events should still be forwarded to the caller submit function")))

(deftest send-path-loss-through-manager-bridge-schedules-reconnect-without-replaying-domain-event
  (let [domain-event {:type :task.command/create
                      :request/id #uuid "11111111-1111-1111-1111-111111111111"
                      :task/name "Take out trash"}
        encoded-payload {:wire/event domain-event}
        disconnected-event {:type :pavlov.web.server/disconnected}
        !transport-opts (atom nil)
        !submitted-events (atom [])
        !timeouts (atom [])
        !connect-calls (atom 0)
        !sent-payloads (atom [])
        fake-transport {:connect! (fn []
                                    (swap! !connect-calls inc)
                                    :connected-socket)
                        :send! (fn [payload]
                                  (swap! !sent-payloads conj payload)
                                  ((:submit-event! @!transport-opts) disconnected-event)
                                  (throw (js/Error. "socket send failed")))
                        :close! (fn [] nil)
                        :encode (fn [event]
                                  {:wire/event event})
                        :decode identity}
        lifecycle (connection/make-browser-websocket-connection!
                   {:ws-path "/ws"
                    :submit! (fn [event]
                               (swap! !submitted-events conj event))
                    :make-transport (fn [opts]
                                      (reset! !transport-opts opts)
                                      fake-transport)
                    :reconnect-delays-ms [123]
                    :set-timeout! (fn [callback delay-ms]
                                    (let [timer-id (str "timer-" (inc (count @!timeouts)))]
                                      (swap! !timeouts conj {:id timer-id
                                                             :callback callback
                                                             :delay-ms delay-ms})
                                      timer-id))
                    :clear-timeout! (fn [_timer-id] nil)})
        bridge-bthread (:bridge-bthread lifecycle)]
    (is (fn? (:submit-event! @!transport-opts))
        "send-path loss should be observable through the real transport submit-event! seam")
    (is (some? bridge-bthread)
        "the manager should expose the bridge bthread used by callers to send domain events")

    (b/notify! bridge-bthread nil)
    (b/notify! bridge-bthread {:type :pavlov.web.server/connected})
    (b/notify! bridge-bthread {:type :pavlov.web.server/send-event
                               :event domain-event})

    (is (= [encoded-payload] @!sent-payloads)
        "the bridge should encode and send the original domain event exactly once before loss is detected")
    (is (= [disconnected-event] @!submitted-events)
        "the transport-submitted disconnected event should be forwarded to the caller")
    (is (= [{:id "timer-1"
             :delay-ms 123}]
           (mapv #(select-keys % [:id :delay-ms]) @!timeouts))
        "send-path loss should schedule one reconnect timer")
    (is (= 0 @!connect-calls)
        "the reconnect should wait for the scheduled timer")

    (when-let [callback (:callback (first @!timeouts))]
      (callback))

    (is (= 1 @!connect-calls)
        "the reconnect timer should reconnect the manager-created transport")
    (is (= [encoded-payload] @!sent-payloads)
        "the original domain event should not be resent automatically after reconnect")))

(deftest heartbeat-lifecycle-starts-after-connected-sends-through-transport-and-stops-on-loss-or-cleanup
  (let [!transport-opts (atom nil)
        !submitted-events (atom [])
        !sent-payloads (atom [])
        !intervals (atom [])
        !cleared-intervals (atom [])
        fake-transport {:connect! (fn []
                                    :connected-socket)
                        :send! (fn [payload]
                                  (swap! !sent-payloads conj payload))
                        :close! (fn [] nil)
                        :encode (fn [event]
                                  [:encoded event])
                        :decode identity}
        lifecycle (connection/make-browser-websocket-connection!
                   {:ws-path "/ws"
                    :submit! (fn [event]
                               (swap! !submitted-events conj event))
                    :make-transport (fn [opts]
                                      (reset! !transport-opts opts)
                                      fake-transport)
                    :heartbeat-interval-ms 4242
                    :set-interval! (fn [callback interval-ms]
                                     (let [interval-id (str "interval-" (inc (count @!intervals)))]
                                       (swap! !intervals conj {:id interval-id
                                                               :callback callback
                                                               :interval-ms interval-ms})
                                       interval-id))
                    :clear-interval! (fn [interval-id]
                                       (swap! !cleared-intervals conj interval-id))})
        connected-event {:type :pavlov.web.server/connected}
        disconnected-event {:type :pavlov.web.server/disconnected}
        heartbeat-event {:type server/heartbeat-type}]
    (is (fn? (:submit-event! @!transport-opts))
        "heartbeat behavior should be driven by the real browser transport submit-event! seam")
    (is (empty? @!intervals)
        "construction alone should not start heartbeat scheduling")

    ((:submit-event! @!transport-opts) connected-event)

    (is (= [connected-event] @!submitted-events)
        "the connected lifecycle event should still be forwarded to the caller submit function")
    (is (= [{:id "interval-1"
             :interval-ms 4242}]
           (mapv #(select-keys % [:id :interval-ms]) @!intervals))
        "a transport-submitted connected event should start heartbeat scheduling with the configured interval")
    (is (fn? (:callback (first @!intervals)))
        "the scheduled heartbeat interval should capture a callback")

    (when-let [callback (:callback (first @!intervals))]
      (callback))

    (is (= [[:encoded heartbeat-event]] @!sent-payloads)
        "the heartbeat callback should send the encoded reserved heartbeat event through the manager-created transport")

    ((:submit-event! @!transport-opts) disconnected-event)
    (is (= ["interval-1"] @!cleared-intervals)
        "a disconnected lifecycle event should clear the active heartbeat interval")

    ((:submit-event! @!transport-opts) connected-event)
    (is (= [{:id "interval-1"
             :interval-ms 4242}
            {:id "interval-2"
             :interval-ms 4242}]
           (mapv #(select-keys % [:id :interval-ms]) @!intervals))
        "a later connected event should start a fresh heartbeat interval")

    ((:cleanup! lifecycle))
    (is (= ["interval-1" "interval-2"] @!cleared-intervals)
        "cleanup should clear the active heartbeat interval")))

(deftest heartbeat-send-failures-are-contained-below-app-semantics
  (let [!transport-opts (atom nil)
        !submitted-events (atom [])
        !intervals (atom [])
        fake-transport {:connect! (fn []
                                    :connected-socket)
                        :send! (fn [_payload]
                                  (throw (js/Error. "heartbeat send failed")))
                        :close! (fn [] nil)
                        :encode (fn [event]
                                  [:encoded event])
                        :decode identity}
        _lifecycle (connection/make-browser-websocket-connection!
                    {:ws-path "/ws"
                     :submit! (fn [event]
                                (swap! !submitted-events conj event))
                     :make-transport (fn [opts]
                                       (reset! !transport-opts opts)
                                       fake-transport)
                     :heartbeat-interval-ms 4242
                     :set-interval! (fn [callback interval-ms]
                                      (let [interval-id (str "interval-" (inc (count @!intervals)))]
                                        (swap! !intervals conj {:id interval-id
                                                                :callback callback
                                                                :interval-ms interval-ms})
                                        interval-id))
                     :clear-interval! (fn [_interval-id] nil)})
        connected-event {:type :pavlov.web.server/connected}]
    (is (fn? (:submit-event! @!transport-opts))
        "heartbeat behavior should be driven by the real browser transport submit-event! seam")

    ((:submit-event! @!transport-opts) connected-event)

    (is (= [{:id "interval-1"
             :interval-ms 4242}]
           (mapv #(select-keys % [:id :interval-ms]) @!intervals))
        "a connected lifecycle event should start deterministic heartbeat scheduling")

    (is (= :no-throw
           (try
             ((:callback (first @!intervals)))
             :no-throw
             (catch js/Error _error
               :threw)))
        "heartbeat transport send failures should be contained inside the timer callback")
    (is (= [connected-event] @!submitted-events)
        "heartbeat transport failures should not emit app/domain events to the caller submit function")))

(deftest cleanup-and-pagehide-teardown-close-transport-clear-reconnect-and-stop-future-reconnects
  (let [!transport-opts (atom nil)
        !submitted-events (atom [])
        !timeouts (atom [])
        !cleared-timeouts (atom [])
        !close-calls (atom 0)
        !pagehide-handler (atom nil)
        fake-transport {:connect! (fn []
                                    :connected-socket)
                        :send! (fn [_payload] nil)
                        :close! (fn []
                                  (swap! !close-calls inc))
                        :encode identity
                        :decode identity}
        lifecycle (connection/make-browser-websocket-connection!
                   {:ws-path "/ws"
                    :submit! (fn [event]
                               (swap! !submitted-events conj event))
                    :make-transport (fn [opts]
                                      (reset! !transport-opts opts)
                                      fake-transport)
                    :reconnect-delays-ms [123]
                    :set-timeout! (fn [callback delay-ms]
                                    (let [timer-id (str "timer-" (inc (count @!timeouts)))]
                                      (swap! !timeouts conj {:id timer-id
                                                             :callback callback
                                                             :delay-ms delay-ms})
                                      timer-id))
                    :clear-timeout! (fn [timer-id]
                                      (swap! !cleared-timeouts conj timer-id))
                    :add-pagehide-listener! (fn [handler]
                                              (reset! !pagehide-handler handler))})
        disconnected-event {:type :pavlov.web.server/disconnected}]
    (is (fn? (:submit-event! @!transport-opts))
        "cleanup behavior is driven by the real browser transport submit-event! seam")
    (is (fn? @!pagehide-handler)
        "the manager should register a pagehide handler that shares cleanup behavior")

    ((:submit-event! @!transport-opts) disconnected-event)
    (is (= [{:id "timer-1"
             :delay-ms 123}]
           (mapv #(select-keys % [:id :delay-ms]) @!timeouts))
        "a disconnected event should create one pending reconnect before cleanup")

    ((:cleanup! lifecycle))
    (is (= 1 @!close-calls)
        "cleanup! should close the manager-created browser transport")
    (is (= ["timer-1"] @!cleared-timeouts)
        "cleanup! should clear the pending reconnect timeout")

    ((:cleanup! lifecycle))
    (is (= 1 @!close-calls)
        "cleanup! should be idempotent and not close the transport twice")
    (is (= ["timer-1"] @!cleared-timeouts)
        "cleanup! should be idempotent and not clear the same timeout twice")

    ((:submit-event! @!transport-opts) disconnected-event)
    (is (= 1 (count @!timeouts))
        "after cleanup, later disconnected events should not schedule reconnect")

    (when (fn? @!pagehide-handler)
      (@!pagehide-handler))
    (is (= 1 @!close-calls)
        "the pagehide handler should invoke the same idempotent cleanup behavior"))

  (let [!pagehide-handler (atom nil)
        !close-calls (atom 0)
        fake-transport {:connect! (fn []
                                    :connected-socket)
                        :send! (fn [_payload] nil)
                        :close! (fn []
                                  (swap! !close-calls inc))
                        :encode identity
                        :decode identity}
        _lifecycle (connection/make-browser-websocket-connection!
                    {:ws-path "/ws"
                     :submit! (fn [_event] nil)
                     :make-transport (fn [_opts]
                                       fake-transport)
                     :set-timeout! (fn [_callback _delay-ms]
                                     :timer)
                     :clear-timeout! (fn [_timer-id] nil)
                     :add-pagehide-listener! (fn [handler]
                                               (reset! !pagehide-handler handler))})]
    (is (fn? @!pagehide-handler)
        "the injected pagehide seam should receive a teardown handler")
    (when (fn? @!pagehide-handler)
      (@!pagehide-handler)
      (@!pagehide-handler))
    (is (= 1 @!close-calls)
        "invoking the pagehide handler should run the same idempotent transport cleanup as cleanup!")))
