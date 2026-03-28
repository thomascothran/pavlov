(ns tech.thomascothran.pavlov.web.server-test
  (:require [cljs.test :refer-macros [deftest is]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.web.server :as server]))

(deftest server-namespace-loads
  (is true))

(deftest make-server-bridge-bthread-blocks-send-events-until-connected
  (let [bthread (server/make-server-bridge-bthread
                 (fn [_] nil)
                 {:connect! (fn [& _] nil)
                  :send! (fn [& _] nil)
                  :close! (fn [& _] nil)
                  :encode identity
                  :decode identity})]
    (is (= {:wait-on #{:pavlov.web.server/connected}
            :block #{:pavlov.web.server/send-event}}
           (b/notify! bthread nil)))))

(deftest make-server-bridge-bthread-encodes-and-sends-semantic-event-after-connected-using-current-websocket
  (let [!calls (atom [])
        !websocket (atom nil)
        websocket-handle {:websocket/id "current"}
        semantic-event {:type :task.command/create
                        :request/id #uuid "11111111-1111-1111-1111-111111111111"
                        :task/name "Take out trash"}
        encoded-payload {:wire/event semantic-event}
        bthread (server/make-server-bridge-bthread
                 (fn [_] nil)
                 {:connect! (fn [& _] nil)
                  :!websocket !websocket
                  :send! (fn [websocket payload]
                           (swap! !calls conj [:send! websocket payload]))
                  :close! (fn [& _] nil)
                  :encode (fn [event]
                            (swap! !calls conj [:encode event])
                            encoded-payload)
                   :decode identity})]
    (b/notify! bthread nil)
    (reset! !websocket websocket-handle)
    (b/notify! bthread {:type :pavlov.web.server/connected})
    (b/notify! bthread {:type :pavlov.web.server/send-event
                        :event semantic-event})
    (is (= [[:encode semantic-event]
            [:send! websocket-handle encoded-payload]]
           @!calls))))

(deftest make-server-bridge-bthread-blocks-send-events-again-after-disconnected
  (let [bthread (server/make-server-bridge-bthread
                 (fn [_] nil)
                 {:connect! (fn [& _] nil)
                   :send! (fn [& _] nil)
                   :close! (fn [& _] nil)
                   :encode identity
                   :decode identity})]
    (b/notify! bthread nil)
    (b/notify! bthread {:type :pavlov.web.server/connected})
    (is (= {:wait-on #{:pavlov.web.server/connected}
            :block #{:pavlov.web.server/send-event}}
           (b/notify! bthread {:type :pavlov.web.server/disconnected})))))

(deftest make-server-bridge-bthread-does-not-call-connect-on-initialization
  (let [!connect-calls (atom [])
        bthread (server/make-server-bridge-bthread
                 (fn [_] nil)
                 {:connect! (fn [callbacks]
                              (swap! !connect-calls conj callbacks)
                              :fake-connection)
                  :send! (fn [& _] nil)
                  :close! (fn [& _] nil)
                  :encode identity
                  :decode identity})]
    (is (= {:wait-on #{:pavlov.web.server/connected}
            :block #{:pavlov.web.server/send-event}}
           (b/notify! bthread nil)))
    (is (= []
           @!connect-calls))))

(deftest make-server-bridge-bthread-initializes-disconnected-when-connect-omitted
  (let [bthread (server/make-server-bridge-bthread
                 (fn [_] nil)
                 {:send! (fn [& _] nil)
                  :close! (fn [& _] nil)
                  :encode identity
                  :decode identity})]
    (is (= {:wait-on #{:pavlov.web.server/connected}
            :block #{:pavlov.web.server/send-event}}
           (b/notify! bthread nil)))))

(deftest make-server-bridge-bthread-transitions-between-connected-and-disconnected-from-host-submitted-events
  (let [bthread (server/make-server-bridge-bthread
                 (fn [_event] nil)
                 {:send! (fn [& _] nil)
                  :close! (fn [& _] nil)
                  :encode identity
                  :decode identity})]
    (is (= {:wait-on #{:pavlov.web.server/send-event
                       :pavlov.web.server/disconnected}}
           (do
             (b/notify! bthread nil)
             (b/notify! bthread {:type :pavlov.web.server/connected}))))
    (is (= {:wait-on #{:pavlov.web.server/connected}
            :block #{:pavlov.web.server/send-event}}
           (b/notify! bthread {:type :pavlov.web.server/disconnected})))))

(deftest make-server-bridge-bthread-allows-host-submitted-event-received-events
  (let [decoded-event {:type :task.event/created
                       :request/id #uuid "11111111-1111-1111-1111-111111111111"
                       :task/id 123}
        bthread (server/make-server-bridge-bthread
                   (fn [_event] nil)
                   {:send! (fn [& _] nil)
                    :close! (fn [& _] nil)
                    :encode identity
                    :decode identity})]
    (b/notify! bthread nil)
    (is (= {:wait-on #{:pavlov.web.server/connected}
            :block #{:pavlov.web.server/send-event}}
           (b/notify! bthread {:type :pavlov.web.server/event-received
                               :event decoded-event})))))

(deftest make-server-bridge-bthread-requests-on-error-follow-up-when-send-throws
  (let [semantic-event {:type :task.command/create
                        :request/id #uuid "11111111-1111-1111-1111-111111111111"
                        :task/name "Take out trash"}
        bthread (server/make-server-bridge-bthread
                 (fn [_] nil)
                 {:connect! (fn [& _] nil)
                  :send! (fn [_]
                           (throw (js/Error. "socket not writable")))
                  :close! (fn [& _] nil)
                  :encode identity
                  :decode identity})]
    (b/notify! bthread nil)
    (b/notify! bthread {:type :pavlov.web.server/connected})
    (let [bid (b/notify! bthread {:type :pavlov.web.server/send-event
                                  :event semantic-event
                                  :on-error-event-type :task.command/send-failed})
          requested-events (:request bid)
          error-event (first requested-events)]
      (is (= {:wait-on #{:pavlov.web.server/send-event
                         :pavlov.web.server/disconnected}}
             (select-keys bid [:wait-on])))
      (is (= 1 (count requested-events)))
      (is (= :task.command/send-failed
             (:type error-event)))
      (is (= semantic-event
             (:event error-event)))
      (is (map? error-event))
       (is (map? (:error error-event)))
       (is (= "socket not writable"
              (get-in error-event [:error :message]))))))

(deftest make-server-bridge-bthread-requests-same-on-error-follow-up-when-encode-throws
  (let [semantic-event {:type :task.command/create
                        :request/id #uuid "11111111-1111-1111-1111-111111111111"
                        :task/name "Take out trash"}
        bthread (server/make-server-bridge-bthread
                 (fn [_] nil)
                 {:connect! (fn [& _] nil)
                  :send! (fn [_]
                           (throw (js/Error. "send should not run after encode failure")))
                  :close! (fn [& _] nil)
                  :encode (fn [_]
                            (throw (js/Error. "encode exploded")))
                  :decode identity})]
    (b/notify! bthread nil)
    (b/notify! bthread {:type :pavlov.web.server/connected})
    (let [bid (b/notify! bthread {:type :pavlov.web.server/send-event
                                  :event semantic-event
                                  :on-error-event-type :task.command/send-failed})
          requested-events (:request bid)
          error-event (first requested-events)]
      (is (= {:wait-on #{:pavlov.web.server/send-event
                         :pavlov.web.server/disconnected}}
             (select-keys bid [:wait-on])))
      (is (= 1 (count requested-events)))
      (is (= :task.command/send-failed
             (:type error-event)))
      (is (= semantic-event
             (:event error-event)))
      (is (map? error-event))
      (is (map? (:error error-event)))
      (is (= "encode exploded"
             (get-in error-event [:error :message]))))))

(deftest make-server-bridge-bthread-supports-fake-transport-connect-send-receive-disconnect-flow
  (let [semantic-event {:type :task.command/create
                        :request/id #uuid "11111111-1111-1111-1111-111111111111"
                        :task/name "Take out trash"}
        encoded-payload {:wire/event semantic-event}
        inbound-raw-payload {:raw/event :task-created}
        decoded-event {:type :task.event/created
                       :request/id #uuid "11111111-1111-1111-1111-111111111111"
                       :task/id 123}
        !submitted-events (atom [])
        !sent-payloads (atom [])
        !bridge (atom nil)
        decode (fn [payload]
                 (is (= inbound-raw-payload payload))
                 decoded-event)
        submit-event! (fn [event]
                        (swap! !submitted-events conj event)
                        (b/notify! @!bridge event))
        bthread (server/make-server-bridge-bthread
                   submit-event!
                   {:send! (fn [payload]
                             (swap! !sent-payloads conj payload))
                    :close! (fn [& _] nil)
                    :encode (fn [event]
                              {:wire/event event})
                    :decode decode})]
    (reset! !bridge bthread)
    (b/notify! bthread nil)
    (submit-event! {:type :pavlov.web.server/connected})
    (b/notify! bthread {:type :pavlov.web.server/send-event
                        :event semantic-event})
    (submit-event! {:type :pavlov.web.server/event-received
                    :event (decode inbound-raw-payload)})
    (submit-event! {:type :pavlov.web.server/disconnected})
    (is (= [encoded-payload]
           @!sent-payloads))
    (is (= [{:type :pavlov.web.server/connected}
            {:type :pavlov.web.server/event-received
             :event decoded-event}
            {:type :pavlov.web.server/disconnected}]
           @!submitted-events))
    (is (= {:wait-on #{:pavlov.web.server/connected}
            :block #{:pavlov.web.server/send-event}}
           (b/notify! bthread {:type :some/other-event})))))
