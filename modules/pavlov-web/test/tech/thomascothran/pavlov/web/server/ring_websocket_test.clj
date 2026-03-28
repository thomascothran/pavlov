(ns tech.thomascothran.pavlov.web.server.ring-websocket-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.web.server :as server]
            [tech.thomascothran.pavlov.web.server.ring-websocket :as ring-websocket]))

(deftest shared-server-bridge-api-does-not-expose-callback-helper-plumbing
  (is (not (contains? (ns-publics 'tech.thomascothran.pavlov.web.server)
                      'make-server-bridge-callbacks))))

(deftest make-ring-websocket-adapter-returns-explicit-ring-listener-and-bridge-seams
  (let [send! (fn [_ws _payload])
        adapter (ring-websocket/make-ring-websocket-adapter
                 {:send! send!
                  :encode (fn [event]
                            {:wire/event event})
                  :send-websocket! (constantly :ok)
                  :submit-event! (constantly :ok)
                  :decode (fn [payload]
                            {:decoded payload})})]
    (testing "the adapter exposes an explicit Ring listener seam"
      (is (map? (:listener adapter)))
      (is (fn? (get-in adapter [:listener :on-open])))
      (is (fn? (get-in adapter [:listener :on-message])))
      (is (fn? (get-in adapter [:listener :on-close])))
      (is (fn? (get-in adapter [:listener :on-error]))))
    (testing "the adapter exposes bridge options that preserve outbound send support"
      (is (map? (:bridge-opts adapter)))
      (is (contains? (:bridge-opts adapter) :send!))
      (is (identical? send!
                      (get-in adapter [:bridge-opts :send!]))))))

(deftest make-ring-websocket-adapter-internally-manages-listeners-around-shared-websocket-cell
  (let [fake-websocket-handle {:websocket/id "fake-ws"}
        raw-payload {:wire/event {:type :task.event/created
                                  :task/id 123}}
        decoded-event {:type :task.event/created
                       :task/id 123}
        !submitted-events (atom [])
        !decoded-payloads (atom [])
        adapter (ring-websocket/make-ring-websocket-adapter
                 {:submit-event! (fn [event]
                                   (swap! !submitted-events conj event))
                  :send-websocket! (constantly :ok)
                  :decode (fn [payload]
                            (swap! !decoded-payloads conj payload)
                            decoded-event)})
        !websocket (get-in adapter [:bridge-opts :!websocket])]
    (is (some? !websocket))
    (is (nil? @!websocket))
    ((get-in adapter [:listener :on-open]) fake-websocket-handle)
    (is (identical? fake-websocket-handle @!websocket)
        "on-open should capture the live websocket handle into :!websocket")
    ((get-in adapter [:listener :on-message]) fake-websocket-handle raw-payload)
    ((get-in adapter [:listener :on-close]) fake-websocket-handle 1000 "normal closure")
    (is (= [raw-payload]
           @!decoded-payloads))
    (is (= [{:type :pavlov.web.server/connected}
            {:type :pavlov.web.server/event-received
             :event decoded-event}
            {:type :pavlov.web.server/disconnected}]
           @!submitted-events))
    (is (nil? @!websocket)
        "on-close should clear :!websocket")))

(deftest make-ring-websocket-adapter-on-open-captures-websocket-for-bridge-sends
  (let [fake-websocket-handle {:websocket/id "fake-ws"}
        outbound-payload {:wire/event {:type :task.command/create
                                       :task/id 123}}
        !submitted-events (atom [])
        !send-websocket-calls (atom [])
        adapter (ring-websocket/make-ring-websocket-adapter
                 {:submit-event! (fn [event]
                                   (swap! !submitted-events conj event))
                  :send-websocket! (fn [websocket payload]
                                     (swap! !send-websocket-calls conj [websocket payload]))
                  :encode identity
                  :decode identity})
        bridge-send! (get-in adapter [:bridge-opts :send!])]
    ((get-in adapter [:listener :on-open]) fake-websocket-handle)
    (is (= [{:type :pavlov.web.server/connected}]
           @!submitted-events))
    (is (fn? bridge-send!))
    (when (fn? bridge-send!)
      (bridge-send! outbound-payload))
    (is (= [[fake-websocket-handle outbound-payload]]
           @!send-websocket-calls))))

(deftest make-ring-websocket-adapter-on-message-decodes-and-submits-shared-event-received
  (let [fake-websocket-handle {:websocket/id "fake-ws"}
        raw-payload {:wire/event {:type :task.command/create
                                  :task/id 123}}
        decoded-event {:type :task.command/create
                       :task/id 123}
        !decoded-payloads (atom [])
        !submitted-events (atom [])
        adapter (ring-websocket/make-ring-websocket-adapter
                 {:submit-event! (fn [event]
                                   (swap! !submitted-events conj event))
                  :send-websocket! (constantly :ok)
                  :decode (fn [payload]
                            (swap! !decoded-payloads conj payload)
                            decoded-event)})]
    ((get-in adapter [:listener :on-message]) fake-websocket-handle raw-payload)
    (is (= [raw-payload]
           @!decoded-payloads))
    (is (= [{:type :pavlov.web.server/event-received
             :event decoded-event}]
           @!submitted-events))))

(deftest make-ring-websocket-adapter-on-close-submits-disconnected-and-clears-captured-websocket
  (let [fake-websocket-handle {:websocket/id "fake-ws"}
        outbound-payload {:wire/event {:type :task.command/create
                                       :task/id 123}}
        !submitted-events (atom [])
        !send-websocket-calls (atom [])
        adapter (ring-websocket/make-ring-websocket-adapter
                 {:submit-event! (fn [event]
                                   (swap! !submitted-events conj event))
                  :send-websocket! (fn [websocket payload]
                                     (swap! !send-websocket-calls conj [websocket payload]))
                  :encode identity
                  :decode identity})
        bridge-send! (get-in adapter [:bridge-opts :send!])]
    ((get-in adapter [:listener :on-open]) fake-websocket-handle)
    (bridge-send! outbound-payload)
    ((get-in adapter [:listener :on-close]) fake-websocket-handle 1000 "normal closure")
    (bridge-send! outbound-payload)
    (is (= [{:type :pavlov.web.server/connected}
            {:type :pavlov.web.server/disconnected}]
           @!submitted-events))
    (is (= [[fake-websocket-handle outbound-payload]]
           @!send-websocket-calls))))

(deftest make-ring-websocket-adapter-on-error-calls-adapter-local-hook-without-submitting-public-event
  (let [fake-websocket-handle {:websocket/id "fake-ws"}
        fake-error (ex-info "fake websocket error" {:error/id ::fake-error})
        !on-error-calls (atom [])
        !submitted-events (atom [])
        adapter (ring-websocket/make-ring-websocket-adapter
                 {:on-error! (fn [websocket error]
                               (swap! !on-error-calls conj [websocket error]))
                  :submit-event! (fn [event]
                                   (swap! !submitted-events conj event))
                  :encode identity
                  :send-websocket! (constantly :ok)
                  :decode identity})]
    ((get-in adapter [:listener :on-error]) fake-websocket-handle fake-error)
    (is (= [[fake-websocket-handle fake-error]]
           @!on-error-calls))
    (is (= []
           @!submitted-events))))

(deftest make-ring-websocket-adapter-on-error-alone-does-not-auto-submit-disconnected
  (let [fake-websocket-handle {:websocket/id "fake-ws"}
        fake-error (ex-info "fake websocket error" {:error/id ::fake-error})
        !submitted-events (atom [])
        adapter (ring-websocket/make-ring-websocket-adapter
                 {:submit-event! (fn [event]
                                   (swap! !submitted-events conj event))
                  :send-websocket! (constantly :ok)
                  :encode identity
                  :decode identity})]
    ((get-in adapter [:listener :on-open]) fake-websocket-handle)
    ((get-in adapter [:listener :on-error]) fake-websocket-handle fake-error)
    (is (= [{:type :pavlov.web.server/connected}]
           @!submitted-events))))

(deftest make-ring-websocket-adapter-supports-full-one-connection-ring-flow-through-shared-bridge
  (let [fake-websocket-handle {:websocket/id "fake-ws"}
        semantic-event {:type :task.command/create
                        :task/id 123}
        encoded-payload {:wire/event semantic-event}
        inbound-raw-payload {:wire/event {:type :task.event/created
                                          :task/id 123}}
        decoded-event {:type :task.event/created
                       :task/id 123}
        !submitted-events (atom [])
        !sent-payloads (atom [])
        !bridge (atom nil)
        submit-event! (fn [event]
                        (swap! !submitted-events conj event)
                        (b/notify! @!bridge event))
        adapter (ring-websocket/make-ring-websocket-adapter
                 {:submit-event! submit-event!
                  :send-websocket! (fn [websocket payload]
                                     (swap! !sent-payloads conj [websocket payload]))
                  :encode (fn [event]
                            {:wire/event event})
                  :decode (fn [payload]
                            (is (= inbound-raw-payload payload))
                            decoded-event)})
        bthread (server/make-server-bridge-bthread submit-event!
                                                   (:bridge-opts adapter))]
    (reset! !bridge bthread)
    (b/notify! bthread nil)
    ((get-in adapter [:listener :on-open]) fake-websocket-handle)
    (b/notify! bthread {:type :pavlov.web.server/send-event
                        :event semantic-event})
    ((get-in adapter [:listener :on-message]) fake-websocket-handle inbound-raw-payload)
    ((get-in adapter [:listener :on-close]) fake-websocket-handle 1000 "normal closure")
    (is (= [[fake-websocket-handle encoded-payload]]
           @!sent-payloads))
    (is (= [{:type :pavlov.web.server/connected}
            {:type :pavlov.web.server/event-received
             :event decoded-event}
            {:type :pavlov.web.server/disconnected}]
           @!submitted-events))
    (is (= {:wait-on #{:pavlov.web.server/connected}
            :block #{:pavlov.web.server/send-event}}
           (b/notify! bthread {:type :some/other-event})))))
