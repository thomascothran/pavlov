(ns pavlov-web-example.game-of-life.client-test
  (:require [cljs.test :refer [async deftest is]]
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

(defn- inert-connection
  [_opts]
  {:start! (fn [] nil)
   :cleanup! (fn [] nil)})

(defn- assert-forwarded-click!
  [selector expected-type expected-server-event done]
  (with-game-of-life-dom
    (fn [document window]
      (let [!events (atom [])
            target (.querySelector document selector)
            lifecycle (client/init! {:make-connection inert-connection})
            program (:program lifecycle)]
        (bp/subscribe! program
                       ::capture
                       (fn [selected-event _]
                         (swap! !events conj selected-event)))
        (.dispatchEvent target (new (.-Event window) "click" #js {:bubbles true}))
        (after-ticks 6
                     (fn []
                       (is (some #(= expected-type (:type %)) @!events))
                       (is (some #(= {:type :pavlov.web.server/send-event
                                      :event expected-server-event}
                                     (select-keys % [:type :event]))
                                 @!events))
                       (bp/stop! program)
                       (done)))))))

(deftest init-forwards-clicked-cell-coordinates-to-the-backend-event
  (async done
         (assert-forwarded-click!
          "[data-game-of-life-cell]"
          :game-of-life/cell-clicked
          {:type :game-of-life/cell-clicked
           :pavlov-row "1"
           :pavlov-col "2"}
          done)))

(deftest init-forwards-reset-clicks-to-the-backend-event
  (async done
         (assert-forwarded-click!
          "#game-of-life-reset-button"
          :game-of-life/reset-clicked
          {:type :game-of-life/reset-clicked}
          done)))

(deftest init-forwards-start-clicks-to-the-backend-event
  (async done
         (assert-forwarded-click!
          "#game-of-life-start-button"
          :game-of-life/start-clicked
          {:type :game-of-life/start-clicked}
          done)))

(deftest init-forwards-pause-clicks-to-the-backend-event
  (async done
         (assert-forwarded-click!
          "#game-of-life-pause-button"
          :game-of-life/pause-clicked
          {:type :game-of-life/pause-clicked}
          done)))
