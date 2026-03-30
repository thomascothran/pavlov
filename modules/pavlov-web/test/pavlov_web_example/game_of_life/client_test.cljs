(ns pavlov-web-example.game-of-life.client-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [pavlov-web-example.game-of-life.client :as client]
            [tech.thomascothran.pavlov.bprogram :as bp]
            ["jsdom" :refer [JSDOM]]))

(defn- after-ticks
  [n f]
  (if (zero? n)
    (f)
    (js/setTimeout #(after-ticks (dec n) f) 0)))

(defn- with-game-of-life-dom
  [f]
  (let [dom (JSDOM. (str "<body data-pavlov-page=\"game-of-life\">"
                          "  <main id=\"game-of-life-root\">"
                          "    <section aria-label=\"controls\">"
                           "      <button id=\"game-of-life-start-button\""
                           "              pavlov-on-click=\":game-of-life/start-clicked\""
                           "              type=\"button\">Start</button>"
                           "      <button id=\"game-of-life-pause-button\""
                           "              pavlov-on-click=\":game-of-life/pause-clicked\""
                           "              type=\"button\">Pause</button>"
                           "      <button id=\"game-of-life-reset-button\""
                           "              pavlov-on-click=\":game-of-life/reset-clicked\""
                           "              type=\"button\">Reset</button>"
                          "    </section>"
                          "    <section data-game-of-life-board>"
                          "      <button data-game-of-life-cell"
                          "              data-row=\"1\""
                         "              data-col=\"2\""
                         "              pavlov-row=\"1\""
                         "              pavlov-col=\"2\""
                         "              pavlov-on-click=\":game-of-life/cell-clicked\""
                         "              type=\"button\">.</button>"
                         "    </section>"
                         "  </main>"
                         "</body>")
                    #js {:url "http://localhost/game-of-life"})
        window (.-window dom)
        document (.-document window)]
    (set! js/global.window window)
    (set! js/global.document document)
    (f document window)))

(deftest init-forwards-clicked-cell-coordinates-to-the-backend-event
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
             (with-game-of-life-dom
               (fn [document window]
                 (let [cell (.querySelector document "[data-game-of-life-cell]")
                       real-global-websocket (.-WebSocket js/global)
                       real-window-websocket (.-WebSocket window)
                       restore-websocket! (fn []
                                            (set! (.-WebSocket js/global) real-global-websocket)
                                            (set! (.-WebSocket window) real-window-websocket))]
                   (set! (.-WebSocket js/global) js/undefined)
                   (set! (.-WebSocket window) js/undefined)
                   (try
                     (client/init!)
                     (.dispatchEvent cell (new (.-Event window) "click" #js {:bubbles true}))
                     (after-ticks 6
                                  (fn []
                                    (is (some #(= :game-of-life/cell-clicked (:type %))
                                              @!events))
                                    (is (some #(= {:type :pavlov.web.server/send-event
                                                   :event {:type :game-of-life/cell-clicked
                                                           :pavlov-row "1"
                                                           :pavlov-col "2"}}
                                                  (select-keys % [:type :event]))
                                              @!events)
                                        "backend toggle events need row/col so the shared board can change the clicked cell")
                                    (restore-websocket!)
                                    (when-let [program @!program]
                                      (bp/stop! program))
                                    (done)))
                     (catch :default error
                        (restore-websocket!)
                        (throw error))))))))))

(deftest init-forwards-reset-clicks-to-the-backend-event
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
             (with-game-of-life-dom
               (fn [document window]
                 (let [reset-button (.querySelector document "#game-of-life-reset-button")
                       real-global-websocket (.-WebSocket js/global)
                       real-window-websocket (.-WebSocket window)
                       restore-websocket! (fn []
                                            (set! (.-WebSocket js/global) real-global-websocket)
                                            (set! (.-WebSocket window) real-window-websocket))]
                   (set! (.-WebSocket js/global) js/undefined)
                   (set! (.-WebSocket window) js/undefined)
                   (try
                     (client/init!)
                     (.dispatchEvent reset-button (new (.-Event window) "click" #js {:bubbles true}))
                     (after-ticks 6
                                  (fn []
                                    (is (some #(= :game-of-life/reset-clicked (:type %))
                                              @!events))
                                    (is (some #(= {:type :pavlov.web.server/send-event
                                                   :event {:type :game-of-life/reset-clicked}}
                                                  (select-keys % [:type :event]))
                                              @!events)
                                        "reset clicks should be forwarded to the shared backend runtime")
                                    (restore-websocket!)
                                    (when-let [program @!program]
                                      (bp/stop! program))
                                    (done)))
                      (catch :default error
                        (restore-websocket!)
                        (throw error))))))))))

(deftest init-forwards-start-clicks-to-the-backend-event
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
             (with-game-of-life-dom
               (fn [document window]
                 (let [start-button (.querySelector document "#game-of-life-start-button")
                       real-global-websocket (.-WebSocket js/global)
                       real-window-websocket (.-WebSocket window)
                       restore-websocket! (fn []
                                            (set! (.-WebSocket js/global) real-global-websocket)
                                            (set! (.-WebSocket window) real-window-websocket))]
                   (set! (.-WebSocket js/global) js/undefined)
                   (set! (.-WebSocket window) js/undefined)
                   (try
                     (client/init!)
                     (.dispatchEvent start-button (new (.-Event window) "click" #js {:bubbles true}))
                     (after-ticks 6
                                  (fn []
                                    (is (some #(= :game-of-life/start-clicked (:type %))
                                              @!events))
                                    (is (some #(= {:type :pavlov.web.server/send-event
                                                   :event {:type :game-of-life/start-clicked}}
                                                  (select-keys % [:type :event]))
                                              @!events)
                                        "start clicks should be forwarded to the backend bridge")
                                    (restore-websocket!)
                                    (when-let [program @!program]
                                      (bp/stop! program))
                                    (done)))
                     (catch :default error
                       (restore-websocket!)
                       (throw error))))))))))

(deftest init-forwards-pause-clicks-to-the-backend-event
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
             (with-game-of-life-dom
               (fn [document window]
                 (let [pause-button (.querySelector document "#game-of-life-pause-button")
                       real-global-websocket (.-WebSocket js/global)
                       real-window-websocket (.-WebSocket window)
                       restore-websocket! (fn []
                                            (set! (.-WebSocket js/global) real-global-websocket)
                                            (set! (.-WebSocket window) real-window-websocket))]
                   (set! (.-WebSocket js/global) js/undefined)
                   (set! (.-WebSocket window) js/undefined)
                   (try
                     (client/init!)
                     (.dispatchEvent pause-button (new (.-Event window) "click" #js {:bubbles true}))
                     (after-ticks 6
                                  (fn []
                                    (is (some #(= :game-of-life/pause-clicked (:type %))
                                              @!events))
                                    (is (some #(= {:type :pavlov.web.server/send-event
                                                   :event {:type :game-of-life/pause-clicked}}
                                                  (select-keys % [:type :event]))
                                              @!events)
                                        "pause clicks should be forwarded to the backend bridge")
                                    (restore-websocket!)
                                    (when-let [program @!program]
                                      (bp/stop! program))
                                    (done)))
                     (catch :default error
                       (restore-websocket!)
                       (throw error))))))))))
