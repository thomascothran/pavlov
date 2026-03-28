(ns pavlov-web-example.browser-only.client
  (:require [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
            [tech.thomascothran.pavlov.bthread :as b]
            [cljs.reader :as reader]
            [pavlov-web-example.browser-only.client.datagrid.in-memory :as datagrid.in-memory]
            [tech.thomascothran.pavlov.web.dom :as dom]
            [tech.thomascothran.pavlov.web.server :as server]
            [tech.thomascothran.pavlov.web.server.websocket :as websocket]))

(def status-selector "[data-browser-only-status]")
(def initialize-selector "nav button")

(defn- websocket-url
  []
  (let [location (.-location js/window)
        protocol (if (= "https:" (.-protocol location))
                   "wss:"
                   "ws:")]
    (str protocol "//" (.-host location) "/browser-only/ws/")))

(defn- decode-event
  [payload]
  (if (string? payload)
    (reader/read-string payload)
    payload))

(defn- status-text
  [event]
  (case (:type event)
    :browser-only/reset "idle"
    (get-in event [:dom/input :value] "")))

(defn- make-program [query-selector submit-event! transport]
  (bpe/make-program!
   (into (cond-> [[:dom-op (dom/make-dom-op-bthread query-selector)]
                  [:dom-event-redirect
                   (dom/make-dom-event-redirect-bthread)]
                  [:mirror-input
                   (b/on-any #{:browser-only/input
                               :browser-only/reset}
                             (fn [event]
                               {:request #{{:type :pavlov.web.dom/op
                                            :selector status-selector
                                            :kind :set
                                            :member "textContent"
                                            :value (status-text event)}}}))]
                  [:initialize-send-event
                   (b/on-any #{:browser-only/initialize-clicked}
                             (fn [event]
                               {:request #{{:type :pavlov.web.server/send-event
                                            :event {:type (:type event)}}}}))]
                  [:server-event-received
                   (b/on-any #{:pavlov.web.server/event-received}
                             (fn [event]
                               {:request #{(:event event)}}))]]
           transport
           (into [[:browser-websocket-bridge
                   (server/make-server-bridge-bthread submit-event! transport)]]))
         (datagrid.in-memory/make-bthreads))))

(defn init! []
  (let [query-selector #(.querySelectorAll js/document %)
        !program (atom nil)
        submit! #(when-let [program @!program]
                   (bp/submit-event! program %))
        transport (when (exists? js/WebSocket)
                    (websocket/make-browser-websocket-transport
                     {:url (websocket-url)
                      :submit-event! submit!
                      :encode pr-str
                      :decode decode-event}))
        program (make-program query-selector submit! transport)]
    (reset! !program program)
    (when transport
      ((:connect! transport)))
    (dom/attach-dom-events! {:root js/document
                             :submit! submit!})))
