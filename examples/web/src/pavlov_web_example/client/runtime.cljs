(ns pavlov-web-example.client.runtime
  (:require [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
            [tech.thomascothran.pavlov.bthread :as b]
            [cljs.reader :as reader]
            [tech.thomascothran.pavlov.web.dom :as dom]
            [tech.thomascothran.pavlov.web.server :as server]
            [tech.thomascothran.pavlov.web.server.websocket :as websocket]))

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
  (bpe/make-program!
   (into (cond-> [[:dom-op (dom/make-dom-op-bthread query-selector)]
                  [:dom-event-redirect
                   (dom/make-dom-event-redirect-bthread)]
                  [:server-event-received
                   (b/on-any #{:pavlov.web.server/event-received}
                             (fn [event]
                               {:request #{(:event event)}}))]]
           (seq forwarded-events)
            (conj [:forward-events
                   (b/on-any forwarded-events
                             (fn [event]
                               {:request #{{:type :pavlov.web.server/send-event
                                            :event (forwarded-event->server-event event)}}}))])
            transport
            (conj [:browser-websocket-bridge
                   (server/make-server-bridge-bthread submit! transport)]))
          page-bthreads)))

(defn init!
  [{:keys [make-program root query-selector ws-path encode decode]
    :or {root js/document
          query-selector #(.querySelectorAll js/document %)}}]
  (let [!program (atom nil)
        submit! #(when-let [program @!program]
                   (bp/submit-event! program %))
        transport (make-browser-transport {:ws-path ws-path
                                           :submit! submit!
                                           :encode encode
                                           :decode decode})
        program (make-program {:query-selector query-selector
                               :submit! submit!
                               :transport transport})]
    (reset! !program program)
    (when transport
      ((:connect! transport)))
    (dom/attach-dom-events! {:root root
                             :submit! submit!})))
