(ns pavlov-web-example.client.runtime
  (:require [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
            [tech.thomascothran.pavlov.bthread :as b]
            [cljs.reader :as reader]
            [tech.thomascothran.pavlov.web.dom :as dom]
            [tech.thomascothran.pavlov.web.server :as server]
            [tech.thomascothran.pavlov.web.server.websocket :as websocket]))

(defn- log
  [& args]
  (.log js/console (apply str "[pavlov-web-example.client] " args)))

(defn websocket-url
  [ws-path]
  (let [location (.-location js/window)
        protocol (if (= "https:" (.-protocol location))
                   "wss:"
                   "ws:")]
    (str protocol "//" (.-host location) ws-path)))

(defn decode-event
  [payload]
  (if (string? payload)
    (reader/read-string payload)
    payload))

(defn make-browser-transport
  [{:keys [ws-path submit! encode decode]
    :or {encode pr-str
         decode decode-event}}]
  (when (exists? js/WebSocket)
    (websocket/make-browser-websocket-transport
     {:url (websocket-url ws-path)
      :submit-event! submit!
      :encode (or encode pr-str)
      :decode (or decode decode-event)})))

(defn make-bridged-program!
  [{:keys [query-selector submit! transport page-bthreads forwarded-events forwarded-event->server-event]
    :or {forwarded-event->server-event (fn [event]
                                         {:type (:type event)})}}]
  (let [forwarded-event->server-event (or forwarded-event->server-event
                                          (fn [event]
                                            {:type (:type event)}))]
    (bpe/make-program!
     (into (cond-> [[:dom-op (dom/make-dom-op-bthread query-selector)]
                    [:dom-event-redirect
                     (dom/make-dom-event-redirect-bthread)]
                    [:server-event-received
                     (b/on-any #{:pavlov.web.server/event-received}
                               (fn [event]
                                 (log "received server event type=" (:type (:event event))
                                      (when-let [ops (:ops (:event event))]
                                        (str " ops=" (count ops))))
                                 {:request #{(:event event)}}))]]
             (seq forwarded-events)
             (conj [:forward-events
                    (b/on-any forwarded-events
                              (fn [event]
                                (log "forwarding event to server type=" (:type event)
                                     " payload=" (pr-str (forwarded-event->server-event event)))
                                {:request #{{:type :pavlov.web.server/send-event
                                             :event (forwarded-event->server-event event)}}}))])
             transport
             (conj [:browser-websocket-bridge
                    (server/make-server-bridge-bthread submit! transport)]))
           page-bthreads))))

(defn init!
  [{:keys [make-program root query-selector ws-path encode decode
           reconnect-delays-ms set-timeout! clear-timeout!
           heartbeat-interval-ms set-interval! clear-interval!
           page-bthreads forwarded-events forwarded-event->server-event]
    :or {root js/document
         query-selector #(.querySelectorAll js/document %)
         reconnect-delays-ms [250 1000 5000 10000]
         set-timeout! js/setTimeout
         clear-timeout! js/clearTimeout
         set-interval! js/setInterval
         clear-interval! js/clearInterval}}]
  (log "init! ws-path=" ws-path)
  (let [!program (atom nil)
        !transport (atom nil)
        !closed? (atom false)
        !reconnect-timeout (atom nil)
        !reconnect-attempt (atom 0)
        !heartbeat-interval (atom nil)
        clear-reconnect! (fn []
                           (when-let [timeout @!reconnect-timeout]
                             (clear-timeout! timeout)
                             (reset! !reconnect-timeout nil)))
        clear-heartbeat! (fn []
                           (when-let [interval @!heartbeat-interval]
                             (clear-interval! interval)
                             (reset! !heartbeat-interval nil)))
        start-heartbeat! (fn []
                           (when (and heartbeat-interval-ms
                                      @!transport
                                      (nil? @!heartbeat-interval))
                             (reset! !heartbeat-interval
                                     (set-interval!
                                      (fn []
                                        (when-let [transport @!transport]
                                          (try
                                            ((:send! transport)
                                             ((:encode transport)
                                              {:type server/heartbeat-type}))
                                            (catch :default error
                                              (log "heartbeat send failed: " (.-message error))))))
                                      heartbeat-interval-ms))))
        connect! (fn []
                   (when-let [transport @!transport]
                     (when-not @!closed?
                       (log "connecting transport")
                       ((:connect! transport)))))
        schedule-reconnect! (fn []
                              (when (and @!transport
                                         (not @!closed?)
                                         (nil? @!reconnect-timeout))
                                (let [attempt @!reconnect-attempt
                                      delay (or (nth reconnect-delays-ms attempt nil)
                                                (last reconnect-delays-ms)
                                                0)]
                                  (swap! !reconnect-attempt inc)
                                  (reset! !reconnect-timeout
                                          (set-timeout!
                                           (fn []
                                             (reset! !reconnect-timeout nil)
                                             (connect!))
                                           delay)))))
        submit! #(when-let [program @!program]
                   (case (:type %)
                     :pavlov.web.server/connected
                     (do
                       (reset! !reconnect-attempt 0)
                       (clear-reconnect!)
                       (start-heartbeat!))

                     :pavlov.web.server/disconnected
                     (do
                       (clear-heartbeat!)
                       (schedule-reconnect!))

                     nil)
                   (bp/submit-event! program %))
        transport (make-browser-transport {:ws-path ws-path
                                           :submit! submit!
                                           :encode encode
                                           :decode decode})
        program (make-program {:query-selector query-selector
                               :submit! submit!
                               :transport transport
                               :page-bthreads page-bthreads
                               :forwarded-events forwarded-events
                               :forwarded-event->server-event forwarded-event->server-event})]
    (reset! !program program)
    (reset! !transport transport)
    (when transport
      (connect!))
    (log "attaching DOM events")
    (dom/attach-dom-events! {:root root
                             :submit! submit!})
    (let [cleanup! (fn []
                     (reset! !closed? true)
                     (clear-reconnect!)
                     (clear-heartbeat!)
                     (when-let [transport @!transport]
                       ((:close! transport))))]
      (when (and (exists? js/window)
                 (.-addEventListener js/window))
        (.addEventListener js/window "pagehide" cleanup!))
      {:cleanup! cleanup!})))
