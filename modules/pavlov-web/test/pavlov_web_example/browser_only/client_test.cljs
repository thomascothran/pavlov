(ns pavlov-web-example.browser-only.client-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [pavlov-web-example.browser-only.client :as client]
            [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.web.dom :as dom]
            ["jsdom" :refer [JSDOM]]))

(defn- after-ticks
  [n f]
  (if (zero? n)
    (f)
    (js/setTimeout #(after-ticks (dec n) f) 0)))

(defn- with-browser-only-dom
  [f]
  (let [dom (JSDOM. (str "<nav>"
                         "  <button id=\"browser-only-initialize-button\" type=\"button\">INITIALIZE</button>"
                         "</nav>"
                         "<main id=\"browser-only-root\">"
                         "  <form id=\"simple-web-page-form\" pavlov-on-reset=\":browser-only/reset\">"
                         "    <label for=\"simple-web-page-input\">Simple web page!</label>"
                         "    <input id=\"simple-web-page-input\""
                         "           name=\"simple-web-page-input\""
                         "           pavlov-on-input=\":browser-only/input\""
                         "           type=\"text\">"
                         "    <button id=\"reset\" type=\"reset\">Reset</button>"
                         "  </form>"
                         "  <div data-browser-only-status>idle</div>"
                         "  <input id=\"telemetry-grid-search\""
                         "         pavlov-capture-selector=\"#telemetry-grid-body > [data-grid-row]\""
                         "         pavlov-on-input=\":grid/search-input\""
                         "         type=\"text\">"
                         "  <table id=\"telemetry-grid\">"
                         "    <thead>"
                         "      <tr>"
                         "        <th><button id=\"sort-node-id\" data-sort-direction=\"asc\""
                         "                    pavlov-grid-body-selector=\"#telemetry-grid-body\""
                         "                    pavlov-grid-id=\"telemetry\""
                         "                    pavlov-on-click=\":grid/sort-clicked\""
                         "                    pavlov-sort-default-direction=\"asc\""
                         "                    pavlov-sort-key=\"node-id\""
                         "                    pavlov-sort-type=\"string\""
                         "                    pavlov-capture-selector=\"#telemetry-grid-body > [data-grid-row], #telemetry-grid-body > [data-grid-row] > [pavlov-node-id-sort-value]\""
                         "                    type=\"button\">Node ID</button></th>"
                         "        <th><button id=\"sort-latency\" data-sort-direction=\"none\""
                         "                    pavlov-grid-body-selector=\"#telemetry-grid-body\""
                         "                    pavlov-grid-id=\"telemetry\""
                         "                    pavlov-on-click=\":grid/sort-clicked\""
                         "                    pavlov-sort-default-direction=\"asc\""
                         "                    pavlov-sort-key=\"latency\""
                         "                    pavlov-sort-type=\"number\""
                         "                    pavlov-capture-selector=\"#telemetry-grid-body > [data-grid-row], #telemetry-grid-body > [data-grid-row] > [pavlov-latency-sort-value]\""
                         "                    type=\"button\">Latency</button></th>"
                         "      </tr>"
                         "    </thead>"
                         "    <tbody id=\"telemetry-grid-body\" data-grid-body data-grid-id=\"telemetry\">"
                         "      <tr id=\"telemetry-row-001\" data-grid-id=\"telemetry\" data-grid-row pavlov-search-value=\"SYNTH-NODE-001 active encrypted pavlov/udp stable\">"
                         "        <td pavlov-node-id-sort-value=\"SYNTH-NODE-001\">SYNTH-NODE-001</td><td pavlov-latency-sort-value=\"0.82\">0.82 ms</td>"
                         "      </tr>"
                         "      <tr id=\"telemetry-row-002\" data-grid-id=\"telemetry\" data-grid-row pavlov-search-value=\"SYNTH-NODE-002 active encrypted pavlov/grpc stable\">"
                         "        <td pavlov-node-id-sort-value=\"SYNTH-NODE-002\">SYNTH-NODE-002</td><td pavlov-latency-sort-value=\"1.14\">1.14 ms</td>"
                         "      </tr>"
                         "      <tr id=\"telemetry-row-042\" data-grid-id=\"telemetry\" data-grid-row pavlov-search-value=\"SYNTH-NODE-042 syncing degraded pavlov/tcp l4_partial\">"
                         "        <td pavlov-node-id-sort-value=\"SYNTH-NODE-042\">SYNTH-NODE-042</td><td pavlov-latency-sort-value=\"12.5\">12.5 ms</td>"
                         "      </tr>"
                         "      <tr id=\"telemetry-row-099\" data-grid-id=\"telemetry\" data-grid-row pavlov-search-value=\"SYNTH-NODE-099 active encrypted pavlov/udp stable\">"
                         "        <td pavlov-node-id-sort-value=\"SYNTH-NODE-099\">SYNTH-NODE-099</td><td pavlov-latency-sort-value=\"0.45\">0.45 ms</td>"
                         "      </tr>"
                         "      <tr id=\"telemetry-row-105\" data-grid-id=\"telemetry\" data-grid-row pavlov-search-value=\"SYNTH-NODE-105 active encrypted pavlov/ws websocket\">"
                         "        <td pavlov-node-id-sort-value=\"SYNTH-NODE-105\">SYNTH-NODE-105</td><td pavlov-latency-sort-value=\"0.98\">0.98 ms</td>"
                         "      </tr>"
                         "      <tr id=\"telemetry-row-211\" data-grid-id=\"telemetry\" data-grid-row pavlov-search-value=\"SYNTH-NODE-211 offline timeout none null\">"
                         "        <td pavlov-node-id-sort-value=\"SYNTH-NODE-211\">SYNTH-NODE-211</td><td pavlov-latency-sort-value=\"9999\">-- timeout --</td>"
                         "      </tr>"
                         "    </tbody>"
                         "  </table>"
                         "</main>")
                    #js {:url "http://localhost/browser-only"})
        window (.-window dom)
        document (.-document window)]
    (set! js/global.window window)
    (set! js/global.document document)
    (f document window)))

(defn- grid-row-order
  [document]
  (->> (array-seq (.querySelectorAll document "#telemetry-grid-body tr"))
       (map (fn [row]
              (.-textContent (.-firstElementChild row))))
       vec))

(defn- visible-grid-row-order
  [document]
  (->> (array-seq (.querySelectorAll document "#telemetry-grid-body tr"))
       (remove #(.-hidden %))
       (map (fn [row]
              (.-textContent (.-firstElementChild row))))
       vec))

(defn- sort-direction
  [document selector]
  (.getAttribute (.querySelector document selector) "data-sort-direction"))

(deftest init-wires-browser-only-input-to-mirror-into-status-display
  (async done
         (with-browser-only-dom
           (fn [document window]
             (let [input (.querySelector document "#simple-web-page-input")
                   status (.querySelector document "[data-browser-only-status]")
                   input-event (new (.-Event window) "input" #js {:bubbles true})]
               (client/init!)
               (set! (.-value input) "hello from pavlov")
               (.dispatchEvent input input-event)
               (after-ticks 4
                            (fn []
                              (is (= "hello from pavlov" (.-textContent status)))
                              (done))))))))

(deftest init-wires-browser-only-reset-to-clear-derived-display-state
  (async done
         (with-browser-only-dom
           (fn [document window]
             (let [form (.querySelector document "#simple-web-page-form")
                   input (.querySelector document "#simple-web-page-input")
                   status (.querySelector document "[data-browser-only-status]")
                   reset-event (new (.-Event window) "reset" #js {:bubbles true})]
               (client/init!)
               (set! (.-value input) "hello from pavlov")
               (set! (.-textContent status) "hello from pavlov")
               (.reset form)
               (.dispatchEvent form reset-event)
               (after-ticks 4
                            (fn []
                              (is (= "idle" (.-textContent status)))
                              (done))))))))

(deftest init-wires-grid-sort-click-to-reorder-rows-ascending
  (async done
         (with-browser-only-dom
           (fn [document window]
             (let [sort-button (.querySelector document "#sort-latency")
                   click-event (new (.-Event window) "click" #js {:bubbles true})]
               (client/init!)
               (.dispatchEvent sort-button click-event)
               (after-ticks 4
                            (fn []
                              (is (= ["SYNTH-NODE-099"
                                      "SYNTH-NODE-001"
                                      "SYNTH-NODE-105"
                                      "SYNTH-NODE-002"
                                      "SYNTH-NODE-042"
                                      "SYNTH-NODE-211"]
                                     (grid-row-order document)))
                              (is (= "none" (sort-direction document "#sort-node-id")))
                              (is (= "asc" (sort-direction document "#sort-latency")))
                              (done))))))))

(deftest init-wires-grid-search-input-to-filter-visible-rows-by-search-value
  (async done
         (with-browser-only-dom
           (fn [document window]
             (let [search-input (.querySelector document "#telemetry-grid-search")
                   input-event (new (.-Event window) "input" #js {:bubbles true})]
               (client/init!)
               (set! (.-value search-input) "degraded")
               (.dispatchEvent search-input input-event)
               (after-ticks 4
                            (fn []
                              (is (= ["SYNTH-NODE-042"]
                                     (visible-grid-row-order document)))
                              (done))))))))

(deftest init-toggles-grid-sort-direction-on-second-click
  (async done
         (with-browser-only-dom
           (fn [document window]
             (let [sort-button (.querySelector document "#sort-latency")]
               (client/init!)
               (.dispatchEvent sort-button (new (.-Event window) "click" #js {:bubbles true}))
               (.dispatchEvent sort-button (new (.-Event window) "click" #js {:bubbles true}))
               (after-ticks 4
                            (fn []
                              (is (= ["SYNTH-NODE-211"
                                      "SYNTH-NODE-042"
                                      "SYNTH-NODE-002"
                                      "SYNTH-NODE-105"
                                      "SYNTH-NODE-001"
                                      "SYNTH-NODE-099"]
                                     (grid-row-order document)))
                              (is (= "desc" (sort-direction document "#sort-latency")))
                              (done))))))))

(deftest init-wires-visible-initialize-button-click-to-request-server-send-event-without-browser-websocket-bridge
  (async done
         (let [real-submit-event! bp/submit-event!
               real-subscribe! bp/subscribe!
               !events (atom [])
               !program (atom nil)]
           (with-redefs [bp/submit-event!
                         (fn [program event]
                           (when (not= program @!program)
                             (reset! !program program)
                             (real-subscribe! program
                                              ::capture
                                              (fn [selected-event _]
                                                (swap! !events conj selected-event))))
                           (real-submit-event! program event))]
             (with-browser-only-dom
               (fn [document window]
                 (let [initialize-button (first (array-seq (.querySelectorAll document "nav button")))
                       real-global-websocket (.-WebSocket js/global)
                       real-window-websocket (.-WebSocket window)
                       restore-websocket! (fn []
                                            (set! (.-WebSocket js/global) real-global-websocket)
                                            (set! (.-WebSocket window) real-window-websocket))]
                   (set! (.-WebSocket js/global) js/undefined)
                   (set! (.-WebSocket window) js/undefined)
                   (try
                     (client/init!)
                     (.dispatchEvent initialize-button (new (.-Event window) "click" #js {:bubbles true}))
                     (after-ticks 6
                                  (fn []
                                    (is (some #(= :browser-only/initialize-clicked (:type %))
                                              @!events))
                                    (is (some #(= {:type :pavlov.web.server/send-event
                                                   :event {:type :browser-only/initialize-clicked}}
                                                  (select-keys % [:type :event]))
                                              @!events))
                                    (restore-websocket!)
                                    (when-let [program @!program]
                                      (bp/stop! program))
                                    (done)))
                     (catch :default error
                       (restore-websocket!)
                       (throw error))))))))))

(deftest init-gates-browser-websocket-send-on-actual-open-before-sending-encoded-initialize-event
  (async done
         (let [real-submit-event! bp/submit-event!
               !submitted-events (atom [])]
           (with-redefs [bp/submit-event!
                         (fn [program event]
                           (swap! !submitted-events conj event)
                           (real-submit-event! program event))]
             (with-browser-only-dom
               (fn [document window]
                 (let [initialize-button (.querySelector document "#browser-only-initialize-button")
                       real-global-websocket (.-WebSocket js/global)
                       real-window-websocket (.-WebSocket window)
                       !socket (atom nil)
                       !sent-payloads (atom [])
                       fake-websocket-constructor (fn [_url _protocols]
                                                    (let [socket (js-obj)]
                                                      (aset socket "send"
                                                            (fn [payload]
                                                              (swap! !sent-payloads conj payload)))
                                                      (reset! !socket socket)
                                                      socket))
                       restore-websocket! (fn []
                                            (set! (.-WebSocket js/global) real-global-websocket)
                                            (set! (.-WebSocket window) real-window-websocket))]
                   (set! (.-WebSocket js/global) fake-websocket-constructor)
                   (set! (.-WebSocket window) fake-websocket-constructor)
                   (try
                     (client/init!)
                     (.dispatchEvent initialize-button (new (.-Event window) "click" #js {:bubbles true}))
                     (after-ticks 6
                                  (fn []
                                    (is (not-any? #(= :pavlov.web.server/connected (:type %))
                                                  @!submitted-events)
                                        "init! should not submit connected before websocket onopen")
                                    (is (= []
                                           @!sent-payloads)
                                        "click before websocket onopen should not send")
                                    (when-let [onopen (some-> @!socket .-onopen)]
                                      (onopen))
                                    (after-ticks 6
                                                 (fn []
                                                   (is (= [(pr-str {:type :browser-only/initialize-clicked})]
                                                          @!sent-payloads)
                                                       "eligible initialize send should flush after websocket onopen")
                                                   (restore-websocket!)
                                                   (done)))))
                     (catch :default error
                       (restore-websocket!)
                       (throw error))))))))))

(deftest init-unwraps-server-event-received-into-contained-dom-op-event
  (async done
         (let [!submit! (atom nil)]
           (with-redefs [dom/attach-dom-events!
                         (fn [{:keys [submit!]}]
                           (reset! !submit! submit!))]
             (with-browser-only-dom
               (fn [document _window]
                 (let [initialize-button (first (array-seq (.querySelectorAll document "nav button")))]
                   (client/init!)
                   (@!submit! {:type :pavlov.web.server/event-received
                               :event {:type :pavlov.web.dom/op
                                       :selector "nav button"
                                       :kind :set
                                       :member "textContent"
                                       :value "initialized"}})
                   (after-ticks 4
                                (fn []
                                  (is (= "initialized"
                                         (.-textContent initialize-button)))
                                  (done))))))))))

(deftest init-starts-browser-websocket-bridge-and-delivers-inbound-dom-op-to-visible-initialize-button
  (async done
         (with-browser-only-dom
           (fn [document window]
             (let [initialize-button (.querySelector document "#browser-only-initialize-button")
                   inbound-dom-op {:type :pavlov.web.dom/op
                                   :selector "#browser-only-initialize-button"
                                   :kind :set
                                   :member "textContent"
                                   :value "initialized"}
                   real-global-websocket (.-WebSocket js/global)
                   real-window-websocket (.-WebSocket window)
                   !socket (atom nil)
                   fake-websocket-constructor (fn [_url _protocols]
                                                (let [socket (js-obj)]
                                                  (reset! !socket socket)
                                                  socket))
                   restore-websocket! (fn []
                                        (set! (.-WebSocket js/global) real-global-websocket)
                                        (set! (.-WebSocket window) real-window-websocket))]
               (set! (.-WebSocket js/global) fake-websocket-constructor)
               (set! (.-WebSocket window) fake-websocket-constructor)
               (try
                 (client/init!)
                 (after-ticks 4
                              (fn []
                                (is (some? @!socket)
                                    "init! should create the browser websocket bridge connection")
                                (when-let [onmessage (some-> @!socket .-onmessage)]
                                  (onmessage #js {:data inbound-dom-op}))
                                (after-ticks 4
                                             (fn []
                                               (is (= "initialized"
                                                      (.-textContent initialize-button)))
                                               (restore-websocket!)
                                               (done)))))
                 (catch :default error
                   (restore-websocket!)
                   (throw error))))))))

(deftest init-connects-browser-websocket-to-mounted-browser-only-route-with-trailing-slash
  (with-browser-only-dom
    (fn [_document window]
      (let [real-global-websocket (.-WebSocket js/global)
            real-window-websocket (.-WebSocket window)
            !url (atom nil)
            fake-websocket-constructor (fn [url _protocols]
                                         (reset! !url url)
                                         (js-obj))
            restore-websocket! (fn []
                                 (set! (.-WebSocket js/global) real-global-websocket)
                                 (set! (.-WebSocket window) real-window-websocket))]
        (set! (.-WebSocket js/global) fake-websocket-constructor)
        (set! (.-WebSocket window) fake-websocket-constructor)
        (try
          (client/init!)
          (is (= "ws://localhost/browser-only/ws/" @!url))
          (finally
            (restore-websocket!)))))))
