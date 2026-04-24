(ns pavlov-web-example.client.runtime-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.bthread :as b]
            [pavlov-web-example.client.runtime :as runtime]
            ["jsdom" :refer [JSDOM]]))

(defn- after-ticks
  [n f]
  (if (zero? n)
    (f)
    (js/setTimeout #(after-ticks (dec n) f) 0)))

(defn- with-runtime-dom
  [f]
  (let [dom (JSDOM. (str "<main>"
                         "  <button id=\"send\" pavlov-on-click=\":runtime-test/send\" type=\"button\">Send</button>"
                         "</main>")
                    #js {:url "http://localhost/runtime-test"})
        window (.-window dom)
        document (.-document window)]
    (set! js/global.window window)
    (set! js/global.document document)
    (f document window)))

(defn- init-runtime!
  [document opts]
  (runtime/init! (merge {:root document
                         :ws-path "/ws"
                         :make-program runtime/make-bridged-program!
                         :forwarded-events #{:runtime-test/send}}
                        opts)))

(deftest init-delegates-websocket-lifecycle-to-connection-manager
  (with-runtime-dom
    (fn [document _window]
      (let [!manager-opts (atom nil)
            !program-opts (atom nil)
            !calls (atom [])
            managed-transport {:transport/id ::managed-transport}
            managed-bridge-bthread [:managed-bridge-bthread]
            make-connection (fn [opts]
                              (reset! !manager-opts opts)
                              {:transport managed-transport
                               :bridge-bthread managed-bridge-bthread
                               :start! (fn []
                                         (swap! !calls conj :start))
                               :cleanup! (fn []
                                           (swap! !calls conj :cleanup))})
            encode (fn [event]
                     [:encoded event])
            decode (fn [payload]
                     [:decoded payload])
            set-timeout! (fn [_f _delay-ms]
                           :timeout-id)
            clear-timeout! (fn [_timeout-id]
                             nil)
            set-interval! (fn [_f _interval-ms]
                            :interval-id)
            clear-interval! (fn [_interval-id]
                              nil)
            add-pagehide-listener! (fn [_f]
                                     :remove-pagehide-listener!)
            make-program (fn [opts]
                           (reset! !program-opts opts)
                           (swap! !calls conj :program)
                           {:program/id ::program})
            lifecycle (init-runtime! document {:make-connection make-connection
                                               :make-program make-program
                                               :ws-path "/runtime-ws"
                                               :encode encode
                                               :decode decode
                                               :reconnect-delays-ms [10 20]
                                               :set-timeout! set-timeout!
                                               :clear-timeout! clear-timeout!
                                               :heartbeat-interval-ms 1000
                                               :set-interval! set-interval!
                                               :clear-interval! clear-interval!
                                               :add-pagehide-listener! add-pagehide-listener!})]
        (is (some? @!manager-opts)
            "init! should ask the connection manager for websocket lifecycle wiring")
        (is (= {:ws-path "/runtime-ws"
                :encode encode
                :decode decode
                :reconnect-delays-ms [10 20]
                :set-timeout! set-timeout!
                :clear-timeout! clear-timeout!
                :heartbeat-interval-ms 1000
                :set-interval! set-interval!
                :clear-interval! clear-interval!
                :add-pagehide-listener! add-pagehide-listener!}
               (select-keys @!manager-opts [:ws-path
                                            :encode
                                            :decode
                                            :reconnect-delays-ms
                                            :set-timeout!
                                            :clear-timeout!
                                            :heartbeat-interval-ms
                                            :set-interval!
                                            :clear-interval!
                                            :add-pagehide-listener!]))
            "runtime should forward websocket lifecycle options and host seams to the connection manager")
        (is (not (contains? @!manager-opts :make-transport))
            "runtime should let the connection manager own the default browser transport factory")
        (is (fn? (:submit! @!manager-opts))
            "runtime should provide the manager a submit! callback into the program")
        (is (= managed-transport (:transport @!program-opts))
            "runtime should pass the manager transport into make-program")
        (is (= managed-bridge-bthread (:bridge-bthread @!program-opts))
            "runtime should install the manager bridge bthread in make-program")
        (when-let [cleanup! (:cleanup! lifecycle)]
          (cleanup!))
        (is (= [:program :start :cleanup]
               @!calls)
            "runtime should create the program, then start the manager, and cleanup through the manager")))))

(deftest make-bridged-program-composes-dom-page-bthreads-and-forwards-configured-events
  (async done
         (with-runtime-dom
           (fn [_document _window]
             (let [!events (atom [])
                   page-bthread (b/on-any #{:runtime-test/page-input}
                                          (fn [event]
                                            {:request #{{:type :runtime-test/page-output
                                                         :source (:source event)}}}))
                   program (runtime/make-bridged-program!
                            {:query-selector (fn [_selector]
                                               #js [])
                             :submit! (fn [_event] nil)
                             :page-bthreads [[:page page-bthread]]
                             :forwarded-events #{:runtime-test/send}
                             :forwarded-event->server-event (fn [event]
                                                              {:type (:type event)
                                                               :id (:id event)})})]
               (bp/subscribe! program
                              ::capture
                              (fn [selected-event _]
                                (swap! !events conj selected-event)))
               (bp/submit-event! program {:type :dom/click
                                          :pavlov-on-click :runtime-test/send
                                          :id 42})
               (bp/submit-event! program {:type :runtime-test/page-input
                                          :source :page})
               (after-ticks 8
                            (fn []
                              (is (some #(= :runtime-test/send (:type %))
                                        @!events)
                                  "DOM redirect bthread should turn configured DOM events into app events")
                              (is (some #(= {:type :pavlov.web.server/send-event
                                             :event {:type :runtime-test/send
                                                     :id 42}}
                                           (select-keys % [:type :event]))
                                        @!events)
                                  "forwarded app events should request a server send event")
                              (is (some #(= {:type :runtime-test/page-output
                                             :source :page}
                                           (select-keys % [:type :source]))
                                        @!events)
                                  "runtime programs should include supplied page bthreads")
                              (bp/stop! program)
                              (done))))))))
