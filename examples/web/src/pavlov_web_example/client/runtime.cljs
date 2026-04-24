(ns pavlov-web-example.client.runtime
  (:require [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
            [tech.thomascothran.pavlov.bthread :as b]
            [cljs.reader :as reader]
            [tech.thomascothran.pavlov.web.dom :as dom]
            [tech.thomascothran.pavlov.web.client.websocket-connection :as websocket-connection]))

(defn- log
  [& args]
  (.log js/console (apply str "[pavlov-web-example.client] " args)))

(defn decode-event
  "Decode the example runtime's EDN websocket payloads.

  String payloads are read as EDN; already-decoded values pass through unchanged
  so tests and alternate transports can inject domain events directly."
  [payload]
  (if (string? payload)
    (reader/read-string payload)
    payload))

(def ^:private manager-lifecycle-option-keys
  [:reconnect-delays-ms
   :set-timeout!
   :clear-timeout!
   :heartbeat-interval-ms
   :set-interval!
   :clear-interval!
   :add-pagehide-listener!])

(defn- with-supplied-manager-lifecycle-options
  [connection-opts runtime-opts]
  (reduce (fn [opts k]
            (if (contains? runtime-opts k)
              (assoc opts k (get runtime-opts k))
              opts))
          connection-opts
          manager-lifecycle-option-keys))

(defn make-bridged-program!
  "Compose the example browser program from app, DOM, and manager bridge bthreads.

  The runtime owns app/page composition and example forwarded-event policy. The
  websocket connection manager supplies `:bridge-bthread`; when omitted, no bridge
  bthread is installed here."
  [{:keys [query-selector bridge-bthread page-bthreads forwarded-events forwarded-event->server-event]
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
             bridge-bthread
             (conj [:browser-websocket-bridge bridge-bthread]))
           page-bthreads))))

(defn init!
  "Initialize the example browser runtime.

  Builds the app program, delegates websocket lifecycle/bridge wiring to
  `:make-connection`, starts that connection, attaches DOM events, and returns
  the manager cleanup handle. Encoding/decoding defaults remain example
  serialization policy; lifecycle defaults remain with the connection manager."
  [{:keys [make-program make-connection root query-selector ws-path encode decode
           page-bthreads forwarded-events forwarded-event->server-event]
    :as opts
    :or {root js/document
         query-selector #(.querySelectorAll js/document %)
         make-connection websocket-connection/make-browser-websocket-connection!}}]
  (log "init! ws-path=" ws-path)
  (let [!program (atom nil)
        submit! #(when-let [program @!program]
                   (bp/submit-event! program %))
        connection (make-connection (with-supplied-manager-lifecycle-options
                                      {:ws-path ws-path
                                       :submit! submit!
                                       :encode (or encode pr-str)
                                       :decode (or decode decode-event)}
                                      opts))
        program (make-program {:query-selector query-selector
                               :submit! submit!
                               :transport (:transport connection)
                               :bridge-bthread (:bridge-bthread connection)
                               :page-bthreads page-bthreads
                               :forwarded-events forwarded-events
                               :forwarded-event->server-event forwarded-event->server-event})]
    (reset! !program program)
    (when-let [start! (:start! connection)]
      (start!))
    (log "attaching DOM events")
    (dom/attach-dom-events! {:root root
                             :submit! submit!})
    {:cleanup! (:cleanup! connection)}))
