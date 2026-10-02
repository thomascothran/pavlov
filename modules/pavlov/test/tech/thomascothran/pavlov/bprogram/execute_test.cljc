(ns tech.thomascothran.pavlov.bprogram.execute-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is async]])
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.defaults]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
            [tech.thomascothran.pavlov.io :as io]))

(def deadlock-event
  {:type :tech.thomascothran.pavlov.bprogram.ephemeral/deadlock
   :terminal true})

(deftest terminate-on-deadlock-default-and-enabled
  #?(:clj
     (doseq [opts [{} {:terminate-on-deadlock true}]]
       (is (= deadlock-event
              (deref (bpe/execute! {:waiting (b/bids [{:wait-on #{:never}}])}
                                  (assoc opts :kill-after 1000))
                     2000 ::timeout))))
     :cljs
     (async done
       (.then (js/Promise.all
               (clj->js
                (for [opts [{} {:terminate-on-deadlock true}]]
                  (.then (bpe/execute! {:waiting (b/bids [{:wait-on #{:never}}])}
                                      (assoc opts :kill-after 1000))
                         (fn [result] (is (= deadlock-event result)))))))
              (fn [_] (done))))))

(deftest disabled-deadlock-detection-waits-until-timeout
  (let [run #(bpe/execute! {:waiting (b/bids [{:wait-on #{:never}}])}
                           {:terminate-on-deadlock false :kill-after 100})
        kill-event {:type :pavlov/kill :terminal true}]
    #?(:clj
       (let [stopped (run)]
         (is (= ::pending (deref stopped 20 ::pending)))
         (is (= kill-event (deref stopped 2000 ::timeout))))
       :cljs
       (async done
         (.then (run)
                (fn [result]
                  (is (= kill-event result))
                  (done)))))))

(deftest disabled-deadlock-detection-allows-io-completion
  (let [terminal-event {:type :done :terminal true}
        run (fn [dispatch!]
              (bpe/execute!
               [[:workflow (b/bids [{:wait-on #{:fetch}}
                                   {:wait-on #{:fetched}}
                                   {:request #{terminal-event}}])]]
               {:terminate-on-deadlock false
                :request-event :fetch
                :kill-after 2000
                :subscribers
                {:io (io/make-subscriber!
                      {:fetch (fn [{:keys [on-complete!]}]
                                (on-complete! {:event :fetched}))}
                      {:dispatch! dispatch!})}}))]
    #?(:clj
       (let [task (promise)
             stopped (run #(deliver task %))
             dispatched-task (deref task 1000 ::timeout)]
         (is (fn? dispatched-task) "The IO request should be dispatched")
         (is (= ::pending (deref stopped 50 ::pending))
             "Waiting for the IO outcome is not a deadlock")
         (when (fn? dispatched-task) (dispatched-task))
         (is (= terminal-event (deref stopped 3000 ::timeout))))
       :cljs
       (async done
         (.then (run #(js/setTimeout % 20))
                (fn [result]
                  (is (= terminal-event result))
                  (done)))))))
