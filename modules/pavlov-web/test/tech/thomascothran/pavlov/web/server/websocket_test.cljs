(ns tech.thomascothran.pavlov.web.server.websocket-test
  (:require [cljs.test :refer-macros [deftest is]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.web.server :as server]
            [tech.thomascothran.pavlov.web.server.websocket :as websocket]))

(deftest make-browser-websocket-transport-connect-creates-websocket-and-wires-bridge-callbacks
  (let [url "wss://example.test/ws"
        protocols #js ["pavlov.edn" "pavlov.v1"]
        fake-socket (js-obj)
        !factory-calls (atom [])
        !connected-calls (atom 0)
        !message-payloads (atom [])
        !disconnected-calls (atom 0)
        transport (websocket/make-browser-websocket-transport
                   {:url url
                    :protocols protocols
                    :websocket-factory (fn [configured-url configured-protocols]
                                         (swap! !factory-calls conj [configured-url configured-protocols])
                                         fake-socket)})
        connection ((:connect! transport)
                    {:on-connected (fn []
                                     (swap! !connected-calls inc))
                     :on-message (fn [payload]
                                   (swap! !message-payloads conj payload))
                     :on-disconnected (fn []
                                        (swap! !disconnected-calls inc))})]
    (is (= [[url protocols]]
           @!factory-calls))
    (is (identical? fake-socket connection))
    (when-let [onopen (.-onopen fake-socket)]
      (onopen))
    (when-let [onmessage (.-onmessage fake-socket)]
      (onmessage #js {:data "raw inbound payload"}))
    (when-let [onclose (.-onclose fake-socket)]
      (onclose))
    (is (= 1 @!connected-calls))
     (is (= ["raw inbound payload"]
            @!message-payloads))
     (is (= 1 @!disconnected-calls))))

(deftest make-browser-websocket-transport-send!-uses-created-websocket-after-connect
  (let [fake-socket (js-obj)
        !sent-payloads (atom [])
        _ (aset fake-socket "send"
                (fn [payload]
                  (swap! !sent-payloads conj payload)))
        transport (websocket/make-browser-websocket-transport
                   {:url "wss://example.test/ws"
                    :websocket-factory (fn [_url _protocols]
                                         fake-socket)})]
    ((:connect! transport)
     {:on-connected (fn [])
      :on-message (fn [_payload])
      :on-disconnected (fn [])})
    (when-let [onopen (.-onopen fake-socket)]
     (onopen))
     ((:send! transport) "outbound payload")
     (is (= ["outbound payload"]
            @!sent-payloads))))

(deftest make-browser-websocket-transport-connect-internally-translates-browser-lifecycle-into-bridge-events
  (let [url "wss://example.test/ws"
        inbound-raw-payload "raw inbound payload"
        decoded-event {:type :task.event/created
                       :request/id #uuid "11111111-1111-1111-1111-111111111111"
                       :task/id 123}
        fake-socket (js-obj)
        !websocket (atom nil)
        !factory-calls (atom [])
        !submitted-events (atom [])
        !bridge (atom nil)
        submit-event! (fn [event]
                        (swap! !submitted-events conj event)
                        (when-let [bridge @!bridge]
                          (b/notify! bridge event)))
        transport (websocket/make-browser-websocket-transport
                   {:url url
                    :!websocket !websocket
                    :submit-event! submit-event!
                    :websocket-factory (fn [configured-url configured-protocols]
                                         (swap! !factory-calls conj [configured-url configured-protocols])
                                         fake-socket)
                    :decode (fn [payload]
                              (is (= inbound-raw-payload payload))
                              decoded-event)})
        bridge (server/make-server-bridge-bthread submit-event! transport)]
    (reset! !bridge bridge)
    (let [connection ((:connect! transport))]
      (is (identical? fake-socket connection)))
    (is (= [[url nil]]
           @!factory-calls))
    (is (= {:wait-on #{:pavlov.web.server/connected}
            :block #{:pavlov.web.server/send-event}}
           (b/notify! bridge nil)))
    (is (nil? @!websocket))
    (when-let [onopen (.-onopen fake-socket)]
      (onopen))
    (is (identical? fake-socket @!websocket))
    (when-let [onmessage (.-onmessage fake-socket)]
      (onmessage #js {:data inbound-raw-payload}))
    (when-let [onclose (.-onclose fake-socket)]
      (onclose))
    (is (= [{:type :pavlov.web.server/connected}
            {:type :pavlov.web.server/event-received
             :event decoded-event}
             {:type :pavlov.web.server/disconnected}]
           @!submitted-events))
    (is (nil? @!websocket))
    (is (= {:wait-on #{:pavlov.web.server/connected}
            :block #{:pavlov.web.server/send-event}}
           (b/notify! bridge {:type :some/other-event})))))

(deftest make-server-bridge-bthread-does-not-start-browser-transport-a-second-time-after-host-connects-it
  (let [url "wss://example.test/ws"
        semantic-event {:type :task.command/create
                        :request/id #uuid "11111111-1111-1111-1111-111111111111"
                        :task/name "Take out trash"}
        encoded-payload {:wire/event semantic-event}
        inbound-raw-payload "raw inbound payload"
        decoded-event {:type :task.event/created
                       :request/id #uuid "11111111-1111-1111-1111-111111111111"
                       :task/id 123}
        first-socket (js-obj)
        second-socket (js-obj)
        !bridge (atom nil)
        !factory-calls (atom [])
        !submitted-events (atom [])
        !sent-payloads (atom [])
        _ (aset first-socket "send"
                (fn [payload]
                  (swap! !sent-payloads conj [:first payload])))
        _ (aset second-socket "send"
                (fn [payload]
                  (swap! !sent-payloads conj [:second payload])))
        transport (websocket/make-browser-websocket-transport
                   {:url url
                    :websocket-factory (fn [configured-url configured-protocols]
                                         (swap! !factory-calls conj [configured-url configured-protocols])
                                         (if (= 1 (count @!factory-calls))
                                           first-socket
                                           second-socket))
                    :encode (fn [event]
                              {:wire/event event})
                    :decode (fn [payload]
                              (is (= inbound-raw-payload payload))
                              decoded-event)})
        submit-event! (fn [event]
                        (swap! !submitted-events conj event)
                        (when-let [bridge @!bridge]
                          (b/notify! bridge event)))
        callbacks (server/make-server-bridge-callbacks submit-event! (:decode transport))
        _ ((:connect! transport) callbacks)
        bridge (server/make-server-bridge-bthread submit-event! transport)]
    (reset! !bridge bridge)
    (is (= [[url nil]]
           @!factory-calls))
    (is (= {:wait-on #{:pavlov.web.server/connected}
            :block #{:pavlov.web.server/send-event}}
           (b/notify! bridge nil)))
    (is (= [[url nil]]
           @!factory-calls))
    (when-let [onopen (.-onopen first-socket)]
      (onopen))
    (b/notify! bridge {:type :pavlov.web.server/send-event
                       :event semantic-event})
    (when-let [onmessage (.-onmessage first-socket)]
      (onmessage #js {:data inbound-raw-payload}))
    (when-let [onclose (.-onclose first-socket)]
      (onclose))
    (is (= [[:first encoded-payload]]
           @!sent-payloads))
    (is (= [{:type :pavlov.web.server/connected}
            {:type :pavlov.web.server/event-received
             :event decoded-event}
            {:type :pavlov.web.server/disconnected}]
           @!submitted-events))))
