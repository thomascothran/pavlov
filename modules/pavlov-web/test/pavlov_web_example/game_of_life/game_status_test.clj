(ns pavlov-web-example.game-of-life.game-status-test
  (:require [clojure.test :refer [deftest is testing]]
            [pavlov-web-example.game-of-life.game-status :as game-status]
            [tech.thomascothran.pavlov.bthread :as b]))

(defn- requested-event
  [bid]
  (first (:request bid)))

(deftest make-game-status-bthread-starts-and-advances-on-tick
  (let [bthread (game-status/make-game-status-bthread)]
    (b/notify! bthread nil)
    (let [start-bid (b/notify! bthread {:type :game-of-life/start-clicked})
          tick-bid (b/notify! bthread {:type :game-of-life/tick})]
      (testing "start emits a status fact and later ticks request one generation advance"
        (is (= {:type :game-of-life/status-changed
                :running? true}
               (requested-event start-bid)))
        (is (= {:type :game-of-life/advance-generation}
               (requested-event tick-bid)))))))

(deftest make-game-status-bthread-pauses-on-reset
  (let [bthread (game-status/make-game-status-bthread)]
    (b/notify! bthread nil)
    (b/notify! bthread {:type :game-of-life/start-clicked})
    (let [reset-bid (b/notify! bthread {:type :game-of-life/reset-clicked})
          tick-bid (b/notify! bthread {:type :game-of-life/tick})]
      (testing "reset emits a paused status change and prevents later ticks from advancing"
        (is (= {:type :game-of-life/status-changed
                :running? false}
               (requested-event reset-bid)))
        (is (nil? (:request tick-bid)))))))

(deftest make-game-status-bthread-emits-snapshots
  (let [bthread (game-status/make-game-status-bthread)
        client-id "client-a"]
    (b/notify! bthread nil)
    (let [bid (b/notify! bthread {:type :game-of-life/get-status-snapshot
                                  :client-id client-id})]
      (testing "status snapshot requests target one client"
        (is (= {:type :game-of-life/status-snapshot
                :client-id client-id
                :running? false}
               (requested-event bid)))))))
