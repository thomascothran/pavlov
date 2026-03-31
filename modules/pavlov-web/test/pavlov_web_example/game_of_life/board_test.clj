(ns pavlov-web-example.game-of-life.board-test
  (:require [clojure.test :refer [deftest is testing]]
            [pavlov-web-example.game-of-life.board :as board]
            [tech.thomascothran.pavlov.bthread :as b]))

(defn- requested-event
  [bid]
  (first (:request bid)))

(deftest make-board-bthread-toggles-a-clicked-cell
  (let [bthread (board/make-board-bthread 3 3)]
    (b/notify! bthread nil)
    (let [bid (b/notify! bthread {:type :game-of-life/cell-clicked
                                  :pavlov-row "1"
                                  :pavlov-col "2"})
          event (requested-event bid)]
      (testing "clicking a dead cell emits an atomic board change"
        (is (= :game-of-life/board-changed (:type event)))
        (is (= #{["1" "2"]} (:created-cells event)))
        (is (= #{} (:killed-cells event)))
        (is (= 0 (:generation event)))
        (is (= 1 (:live-count event)))))))

(deftest make-board-bthread-advances-one-generation
  (let [bthread (board/make-board-bthread 3 3)]
    (b/notify! bthread nil)
    (doseq [coords [["1" "0"] ["1" "1"] ["1" "2"]]]
      (b/notify! bthread {:type :game-of-life/cell-clicked
                          :pavlov-row (first coords)
                          :pavlov-col (second coords)}))
    (let [bid (b/notify! bthread {:type :game-of-life/advance-generation})
          event (requested-event bid)]
      (testing "advancing a horizontal blinker yields the vertical blinker"
        (is (= #{["0" "1"] ["2" "1"]} (:created-cells event)))
        (is (= #{["1" "0"] ["1" "2"]} (:killed-cells event)))
        (is (= 1 (:generation event)))
        (is (= 3 (:live-count event)))))))

(deftest make-board-bthread-emits-targeted-snapshot
  (let [bthread (board/make-board-bthread 3 3)
        client-id "late-joiner"]
    (b/notify! bthread nil)
    (b/notify! bthread {:type :game-of-life/cell-clicked
                        :pavlov-row "1"
                        :pavlov-col "1"})
    (let [bid (b/notify! bthread {:type :game-of-life/get-board-snapshot
                                  :client-id client-id})
          event (requested-event bid)]
      (testing "snapshot requests return the full current board for one client"
        (is (= :game-of-life/board-snapshot (:type event)))
        (is (= client-id (:client-id event)))
        (is (= #{["1" "1"]} (:live-cells event)))
        (is (= 0 (:generation event)))
        (is (= 1 (:live-count event)))))))
