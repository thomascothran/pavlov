(ns pavlov-web-example.game-of-life.websocket-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [pavlov-web-example.game-of-life.websocket :as websocket]
            [ring.websocket]))

(def target-row "1")
(def target-col "2")

(def toggle-cell-payload
  (pr-str {:type :game-of-life/cell-clicked
           :pavlov-row target-row
           :pavlov-col target-col}))

(def start-payload
  (pr-str {:type :game-of-life/start-clicked}))

(def pause-payload
  (pr-str {:type :game-of-life/pause-clicked}))

(def tick-payload
  (pr-str {:type :game-of-life/tick}))

(def blinker-seed
  [["1" "0"]
   ["1" "1"]
   ["1" "2"]])

(def evolved-blinker-live-cells
  #{["0" "1"]
    ["1" "1"]
    ["2" "1"]})

(defn- sent-events-for
  [sent-payloads websocket]
  (->> sent-payloads
       (filter (fn [[sent-websocket _payload]]
                 (= websocket sent-websocket)))
       (map (fn [[_websocket payload]]
              (edn/read-string payload)))))

(defn- dom-ops
  [event]
  (case (:type event)
    :pavlov.web.dom/op [event]
    :pavlov.web.dom/ops (:ops event)
    []))

(defn- cell-update-event?
  [event]
  (some (fn [{:keys [selector]}]
          (and (string? selector)
               (.contains selector "data-game-of-life-cell")
               (.contains selector (str "data-row=\"" target-row "\""))
                (.contains selector (str "data-col=\"" target-col "\""))))
        (dom-ops event)))

(defn- game-of-life-cell-op?
  [{:keys [selector]}]
  (and (string? selector)
       (.contains selector "data-game-of-life-cell")))

(defn- status-op?
  [{:keys [selector]}]
  (= "[data-game-of-life-status]" selector))

(defn- selector->coords
  [selector]
  (let [[_ row col] (re-find #"data-row=\"([^\"]+)\".*data-col=\"([^\"]+)\"" selector)]
    [row col]))

(defn- board-state-from-events
  ([events]
   (board-state-from-events
    (into {}
          (for [row (map str (range 3))
                col (map str (range 3))]
            [[row col] "."]))
    events))
  ([initial-board events]
   (reduce (fn [board {:keys [selector value] :as op}]
             (if (game-of-life-cell-op? op)
               (assoc board (selector->coords selector) value)
               board))
           initial-board
           (mapcat dom-ops events))))

(defn- expected-board-state
  [live-cells]
  (into {}
        (for [row (map str (range 3))
              col (map str (range 3))]
          [[row col] (if (contains? live-cells [row col]) "O" ".")])))

(def seed-board
  (expected-board-state (set blinker-seed)))

(defn- click-cell!
  [listener websocket [row col]]
  ((:on-message listener) websocket (pr-str {:type :game-of-life/cell-clicked
                                             :pavlov-row row
                                             :pavlov-col col})))

(defn- wait-for
  [pred]
  (loop [attempts-left 100]
    (cond
      (pred) true
      (zero? attempts-left) false
      :else (do
              (Thread/sleep 10)
              (recur (dec attempts-left))))))

(defn- test-request []
  {::websocket/runtime-id (random-uuid)
   ::websocket/start-clock? false})

(defn- connect-listener
  [handler-fn request websocket]
  (let [response (handler-fn request)
         listener (:ring.websocket/listener response)]
     ((:on-open listener) websocket)
     listener))

(deftest handler-broadcasts-one-client-cell-toggle-to-all-connected-clients
  (let [handler-fn websocket/handler
        request (test-request)
        websocket-a {:websocket/id "client-a"}
        websocket-b {:websocket/id "client-b"}
        !sent-payloads (atom [])]
    (with-redefs [ring.websocket/send (fn [websocket payload]
                                        (swap! !sent-payloads conj [websocket payload]))]
      (let [listener-a (connect-listener handler-fn request websocket-a)
            _listener-b (connect-listener handler-fn request websocket-b)
            _ (is (wait-for #(seq (sent-events-for @!sent-payloads websocket-a)))
                  "opening client a should eventually emit its initial snapshot")
            _ (is (wait-for #(seq (sent-events-for @!sent-payloads websocket-b)))
                  "opening client b should eventually emit its initial snapshot")
            baseline-count (count @!sent-payloads)]
        ((:on-message listener-a) websocket-a toggle-cell-payload)
        (is (wait-for #(> (count @!sent-payloads) baseline-count))
            "toggling a cell should eventually emit outbound updates")
        (let [new-sends (drop baseline-count @!sent-payloads)
              client-a-events (vec (sent-events-for new-sends websocket-a))
              client-b-events (vec (sent-events-for new-sends websocket-b))]
          (testing "the same cell update is sent to both connected clients"
            (is (seq client-a-events)
                "toggling a cell should emit at least one outbound update")
            (is (= client-a-events client-b-events)
                "both clients should receive the same outbound DOM updates for the shared board")
            (is (some cell-update-event? client-a-events)
                "the outbound update should target the toggled shared-board cell")))))))

(deftest handler-sends-current-board-snapshot-to-late-joining-client
  (let [handler-fn websocket/handler
        request (test-request)
        websocket-a {:websocket/id "client-a"}
        websocket-b {:websocket/id "client-b"}
        !sent-payloads (atom [])]
    (with-redefs [ring.websocket/send (fn [websocket payload]
                                        (swap! !sent-payloads conj [websocket payload]))]
      (let [listener-a (connect-listener handler-fn request websocket-a)
            _ (is (wait-for #(seq (sent-events-for @!sent-payloads websocket-a)))
                  "opening the first client should eventually emit its initial snapshot")
            sends-before-toggle (count @!sent-payloads)]
        ((:on-message listener-a) websocket-a toggle-cell-payload)
        (is (wait-for #(> (count @!sent-payloads) sends-before-toggle))
            "the first toggle should eventually emit outbound updates")
        (let [sends-before-late-join (count @!sent-payloads)]
          (connect-listener handler-fn request websocket-b)
          (is (wait-for #(> (count @!sent-payloads) sends-before-late-join))
              "opening a late client should eventually emit an initial snapshot")
          (let [late-join-events (vec (sent-events-for (drop sends-before-late-join @!sent-payloads)
                                                       websocket-b))]
            (testing "a new connection receives the current shared board state on open"
              (is (> (count @!sent-payloads) sends-before-toggle)
                  "the first toggle should have produced some outbound board update")
               (is (seq late-join-events)
                   "opening a late client should push an initial board snapshot")
               (is (some cell-update-event? late-join-events)
                   "the late-join snapshot should include the already-toggled live cell"))))))))

(deftest handler-broadcasts-cleared-board-and-late-joiners-see-reset-state
  (let [handler-fn websocket/handler
        request (test-request)
        websocket-a {:websocket/id "client-a"}
        websocket-b {:websocket/id "client-b"}
        websocket-c {:websocket/id "client-c"}
        reset-payload (pr-str {:type :game-of-life/reset-clicked})
        !sent-payloads (atom [])]
    (with-redefs [ring.websocket/send (fn [websocket payload]
                                        (swap! !sent-payloads conj [websocket payload]))]
      (let [listener-a (connect-listener handler-fn request websocket-a)
            _listener-b (connect-listener handler-fn request websocket-b)
            _ (is (wait-for #(seq (sent-events-for @!sent-payloads websocket-a)))
                  "opening client a should eventually emit its initial snapshot")
            _ (is (wait-for #(seq (sent-events-for @!sent-payloads websocket-b)))
                  "opening client b should eventually emit its initial snapshot")
            sends-before-toggle (count @!sent-payloads)]
        ((:on-message listener-a) websocket-a toggle-cell-payload)
        (is (wait-for #(> (count @!sent-payloads) sends-before-toggle))
            "the setup toggle should eventually emit an outbound board update")
        (let [sends-before-reset (count @!sent-payloads)]
          ((:on-message listener-a) websocket-a reset-payload)
          (is (wait-for #(> (count @!sent-payloads) sends-before-reset))
              "reset should eventually emit outbound updates")
          (let [reset-sends (drop sends-before-reset @!sent-payloads)
                client-a-events (vec (sent-events-for reset-sends websocket-a))
                client-b-events (vec (sent-events-for reset-sends websocket-b))
                cleared-cell? (fn [event]
                                (some (fn [{:keys [value]}]
                                        (= "." value))
                                      (filter cell-update-event? (dom-ops event))))]
            (testing "reset clears the shared board for connected clients"
              (is (> (count @!sent-payloads) sends-before-toggle)
                  "the setup toggle should have produced an outbound board update")
              (is (seq client-a-events)
                  "reset should emit at least one outbound update")
              (is (= client-a-events client-b-events)
                  "all connected clients should receive the same cleared-board update")
              (is (some cleared-cell? client-a-events)
                  "reset should send a cleared cell value for the previously toggled shared cell"))
            (let [sends-before-late-join (count @!sent-payloads)]
              (connect-listener handler-fn request websocket-c)
              (is (wait-for #(> (count @!sent-payloads) sends-before-late-join))
                  "opening a client after reset should eventually emit a board snapshot")
              (let [late-join-events (vec (sent-events-for (drop sends-before-late-join @!sent-payloads)
                                                           websocket-c))]
                (testing "late joiners see the cleared board after reset"
                  (is (seq late-join-events)
                      "opening a client after reset should push a board snapshot")
                  (is (some cleared-cell? late-join-events)
                      "the late-join snapshot should show the previously toggled cell as cleared"))))))))))

(deftest handler-start-advances-shared-board-on-scheduled-evolution-and-broadcasts-to-all-clients
  (let [handler-fn websocket/handler
        request (test-request)
        websocket-a {:websocket/id "client-a"}
        websocket-b {:websocket/id "client-b"}
        !sent-payloads (atom [])
        expected-board (expected-board-state evolved-blinker-live-cells)]
    (with-redefs [ring.websocket/send (fn [websocket payload]
                                        (swap! !sent-payloads conj [websocket payload]))]
      (let [listener-a (connect-listener handler-fn request websocket-a)
            _listener-b (connect-listener handler-fn request websocket-b)]
        (is (wait-for #(seq (sent-events-for @!sent-payloads websocket-a)))
            "opening client a should eventually emit its initial snapshot")
        (is (wait-for #(seq (sent-events-for @!sent-payloads websocket-b)))
            "opening client b should eventually emit its initial snapshot")
        (doseq [coords blinker-seed]
          (click-cell! listener-a websocket-a coords))
        (is (wait-for #(= seed-board
                          (board-state-from-events (sent-events-for @!sent-payloads websocket-a))))
            "seeding the board should eventually establish the blinker seed")
        (let [sends-before-start (count @!sent-payloads)]
          ((:on-message listener-a) websocket-a start-payload)
          ((:on-message listener-a) websocket-a tick-payload)
          (is (wait-for #(> (count @!sent-payloads) sends-before-start))
              "start and one tick should eventually emit outbound updates")
          (let [new-sends (drop sends-before-start @!sent-payloads)
                client-a-events (vec (sent-events-for new-sends websocket-a))
                client-b-events (vec (sent-events-for new-sends websocket-b))]
            (testing "starting the shared game advances one scheduled generation for every connected client"
              (is (seq client-a-events)
                  "starting and firing one scheduled evolution should emit outbound updates")
              (is (= client-a-events client-b-events)
                  "all connected clients should observe the same evolved shared board")
              (is (= expected-board
                     (board-state-from-events seed-board client-a-events))
                  "the broadcast should render the blinker one generation later as a vertical line"))))))))

(deftest handler-pause-prevents-further-scheduled-shared-evolution
  (let [handler-fn websocket/handler
        request (test-request)
        websocket-a {:websocket/id "client-a"}
        !sent-payloads (atom [])
        expected-board (expected-board-state evolved-blinker-live-cells)]
    (with-redefs [ring.websocket/send (fn [websocket payload]
                                        (swap! !sent-payloads conj [websocket payload]))]
      (let [listener-a (connect-listener handler-fn request websocket-a)]
        (is (wait-for #(seq (sent-events-for @!sent-payloads websocket-a)))
            "opening the client should eventually emit its initial snapshot")
        (doseq [coords blinker-seed]
          (click-cell! listener-a websocket-a coords))
        (is (wait-for #(= seed-board
                          (board-state-from-events (sent-events-for @!sent-payloads websocket-a))))
            "seeding the board should eventually establish the blinker seed")
        ((:on-message listener-a) websocket-a start-payload)
        ((:on-message listener-a) websocket-a tick-payload)
        (is (wait-for #(= expected-board
                          (board-state-from-events
                           (sent-events-for @!sent-payloads websocket-a))))
            "the first tick should eventually establish the evolved board")
        (let [sends-before-pause (count @!sent-payloads)]
          ((:on-message listener-a) websocket-a pause-payload)
          (is (wait-for #(> (count @!sent-payloads) sends-before-pause))
              "pausing should eventually emit a status update")
          (let [sends-before-post-pause-tick (count @!sent-payloads)]
            ((:on-message listener-a) websocket-a tick-payload)
            (Thread/sleep 50)
            (let [pause-events (vec (sent-events-for (subvec (vec @!sent-payloads)
                                                             sends-before-pause
                                                             sends-before-post-pause-tick)
                                                     websocket-a))
                  post-pause-events (vec (sent-events-for (drop sends-before-post-pause-tick @!sent-payloads)
                                                          websocket-a))]
              (testing "pausing stops later scheduled evolution callbacks from changing the shared board"
                (is (= expected-board
                       (board-state-from-events
                        (sent-events-for @!sent-payloads websocket-a)))
                    "the first scheduled evolution should establish the evolved board before pause")
                (is (some (fn [event]
                            (some status-op? (dom-ops event)))
                          pause-events)
                    "pausing should emit a status update for the connected client")
                (is (empty? post-pause-events)
                    "once paused, another tick should not emit further board updates")))))))))

(deftest handler-late-joiner-sees-current-evolved-shared-board-after-started-generation
  (let [handler-fn websocket/handler
        request (test-request)
        websocket-a {:websocket/id "client-a"}
        websocket-b {:websocket/id "client-b"}
        !sent-payloads (atom [])
        expected-board (expected-board-state evolved-blinker-live-cells)]
    (with-redefs [ring.websocket/send (fn [websocket payload]
                                        (swap! !sent-payloads conj [websocket payload]))]
      (let [listener-a (connect-listener handler-fn request websocket-a)]
        (is (wait-for #(seq (sent-events-for @!sent-payloads websocket-a)))
            "opening the client should eventually emit its initial snapshot")
        (doseq [coords blinker-seed]
          (click-cell! listener-a websocket-a coords))
        (is (wait-for #(= seed-board
                          (board-state-from-events (sent-events-for @!sent-payloads websocket-a))))
            "seeding the board should eventually establish the blinker seed")
        ((:on-message listener-a) websocket-a start-payload)
        ((:on-message listener-a) websocket-a tick-payload)
        (is (wait-for #(= expected-board
                          (board-state-from-events (sent-events-for @!sent-payloads websocket-a))))
            "the evolved board should be established before the late joiner connects")
        (let [sends-before-late-join (count @!sent-payloads)]
          (connect-listener handler-fn request websocket-b)
          (is (wait-for #(> (count @!sent-payloads) sends-before-late-join))
              "opening a late client should eventually emit a board snapshot")
          (let [late-join-events (vec (sent-events-for (drop sends-before-late-join @!sent-payloads)
                                                       websocket-b))]
            (testing "a client connecting after one shared generation sees the evolved board snapshot"
              (is (seq late-join-events)
                  "opening a client after a scheduled evolution should push a board snapshot")
              (is (= expected-board (board-state-from-events late-join-events))
                  "the late joiner should receive the current evolved shared board, not the original seed"))))))))

(deftest handler-emits-visible-status-updates-on-connect-start-pause-and-reset
  (let [handler-fn websocket/handler
        request (test-request)
        websocket-a {:websocket/id "client-a"}
        reset-payload (pr-str {:type :game-of-life/reset-clicked})
        !sent-payloads (atom [])]
    (with-redefs [ring.websocket/send (fn [websocket payload]
                                        (swap! !sent-payloads conj [websocket payload]))]
      (let [sends-before-open (count @!sent-payloads)
            listener-a (connect-listener handler-fn request websocket-a)
            _ (is (wait-for #(> (count @!sent-payloads) sends-before-open))
                  "opening the websocket should eventually emit a visible status update")
            open-events (vec (sent-events-for (drop sends-before-open @!sent-payloads)
                                              websocket-a))
            sends-before-start (count @!sent-payloads)]
        ((:on-message listener-a) websocket-a start-payload)
        (is (wait-for #(> (count @!sent-payloads) sends-before-start))
            "starting should eventually emit a visible status update")
        (let [start-events (vec (sent-events-for (drop sends-before-start @!sent-payloads)
                                                 websocket-a))
              sends-before-pause (count @!sent-payloads)]
          ((:on-message listener-a) websocket-a pause-payload)
          (is (wait-for #(> (count @!sent-payloads) sends-before-pause))
              "pausing should eventually emit a visible status update")
          (let [pause-events (vec (sent-events-for (drop sends-before-pause @!sent-payloads)
                                                    websocket-a))
                sends-before-reset (count @!sent-payloads)]
            ((:on-message listener-a) websocket-a reset-payload)
            (is (wait-for #(> (count @!sent-payloads) sends-before-reset))
                "resetting should eventually emit a visible status update")
            (let [reset-events (vec (sent-events-for (drop sends-before-reset @!sent-payloads)
                                                      websocket-a))]
              (testing "the backend pushes a visible status DOM update for each lifecycle transition"
                (is (some (fn [event]
                            (some status-op? (dom-ops event)))
                          open-events)
                    "opening the websocket should replace the placeholder waiting status")
                (is (some (fn [event]
                            (some status-op? (dom-ops event)))
                          start-events)
                    "starting should emit a visible status update")
                (is (some (fn [event]
                            (some status-op? (dom-ops event)))
                          pause-events)
                    "pausing should emit a visible status update")
                (is (some (fn [event]
                            (some status-op? (dom-ops event)))
                          reset-events)
                    "resetting should emit a visible status update")))))))))
