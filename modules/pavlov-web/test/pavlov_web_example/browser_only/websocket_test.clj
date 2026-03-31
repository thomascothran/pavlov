(ns pavlov-web-example.browser-only.websocket-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.websocket]
            [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]))

(def websocket-make-bthreads-var
  'pavlov-web-example.browser-only.websocket/make-bthreads)

(def websocket-handler-var
  'pavlov-web-example.browser-only.websocket/handler)

(def expected-initialize-dom-op-event
  {:type :pavlov.web.dom/op
   :selector "#browser-only-initialize-button"
   :kind :set
   :member "textContent"
   :value "initialized"})

(deftest make-bthreads-turns-initialize-click-into-send-event-for-button-text
  (let [make-bthreads (requiring-resolve websocket-make-bthreads-var)
        !selected-events (atom [])
        program (bpe/make-program! (make-bthreads))]
    (bp/subscribe! program
                   ::capture
                   (fn [selected-event _]
                     (swap! !selected-events conj selected-event)))
    (bp/submit-event! program {:type :browser-only/initialize-clicked})
    @(bp/stop! program)
    (is (some #(= {:type :pavlov.web.server/send-event
                   :event expected-initialize-dom-op-event}
                 (select-keys % [:type :event]))
               @!selected-events))))

(deftest make-bthreads-unwraps-server-websocket-wrapper-event-before-handling-initialize-click
  (let [make-bthreads (requiring-resolve websocket-make-bthreads-var)
        !selected-events (atom [])
        program (bpe/make-program! (make-bthreads))]
    (bp/subscribe! program
                   ::capture
                   (fn [selected-event _]
                     (swap! !selected-events conj selected-event)))
    (bp/submit-event! program {:type :pavlov.web.server/event-received
                               :event {:type :browser-only/initialize-clicked}})
    @(bp/stop! program)
    (is (some #(= {:type :pavlov.web.server/send-event
                   :event expected-initialize-dom-op-event}
                 (select-keys % [:type :event]))
               @!selected-events))))

(deftest handler-returns-ring-websocket-listener-that-roundtrips-one-initialize-click
  (let [handler-fn (requiring-resolve websocket-handler-var)
        fake-websocket-handle {:websocket/id "fake-ws"}
        initialize-clicked-payload (pr-str {:type :browser-only/initialize-clicked})
        expected-dom-op-payload (pr-str expected-initialize-dom-op-event)
        !sent-payloads (atom [])]
    (with-redefs [ring.websocket/send (fn [websocket payload]
                                        (swap! !sent-payloads conj [websocket payload]))]
      (let [response (handler-fn {:websocket? true})
            listener (:ring.websocket/listener response)]
        (testing "handler returns a Ring websocket listener response"
          (is (map? response))
          (is (map? listener))
          (is (fn? (:on-open listener)))
          (is (fn? (:on-message listener))))
        (when (and (fn? (:on-open listener))
                   (fn? (:on-message listener)))
          ((:on-open listener) fake-websocket-handle)
          ((:on-message listener)
           fake-websocket-handle
           initialize-clicked-payload)
          (is (= [[fake-websocket-handle expected-dom-op-payload]]
                 @!sent-payloads))
          (when (fn? (:on-close listener))
            ((:on-close listener) fake-websocket-handle 1000 "normal closure")))))))
