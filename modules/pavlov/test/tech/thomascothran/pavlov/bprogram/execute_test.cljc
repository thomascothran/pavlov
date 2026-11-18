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

;; Subscriber replies under the deadlock detector: an event a subscriber
;; returns as `{:event ...}` is requested before the next event is selected,
;; so `::deadlock` cannot pre-empt it unless it is blocked.

(defn- run-with-reply-subscriber
  "Run `bthreads` with `execute!` and a subscriber that replies to each event in
  `replies` (a map of event to reply). Calls `check` with the terminal event and
  the events the subscriber saw."
  [bthreads replies check]
  (let [!seen (atom [])
        stopped (bpe/execute!
                 bthreads
                 {:kill-after 1000
                  :subscribers
                  {:reply (fn [e _]
                            (swap! !seen conj e)
                            (when-let [r (get replies e)]
                              {:event r}))}})]
    #?(:clj (check (deref stopped 2000 ::timeout) @!seen)
       :cljs (async done
               (.then stopped
                      (fn [result]
                        (check result @!seen)
                        (done)))))))

(deftest execute-selects-subscriber-reply-before-deadlock
  (let [reply {:type :reply :terminal true}]
    (run-with-reply-subscriber
     [[:start (b/bids [{:request #{:start}}])]]
     {:start reply}
     (fn [result _]
       (is (= reply result))))))

(deftest execute-selects-subscriber-replies-after-internal-requests-in-order
  (run-with-reply-subscriber
   [[:producer (b/bids [{:request #{:start}}
                        {:request #{:middle}}
                        {:request #{:last}}])]]
   {:start :start-reply
    :middle :middle-reply
    :last :last-reply}
   (fn [result seen]
     (is (= deadlock-event result))
     (is (= [:start :middle :last :start-reply :middle-reply :last-reply
             deadlock-event]
            seen)))))

(deftest execute-deadlocks-when-subscriber-reply-is-blocked
  (run-with-reply-subscriber
   [[:block-reply {:block #{:reply}}]
    [:start (b/bids [{:request #{:start}}])]]
   {:start :reply}
   (fn [result seen]
     (is (= deadlock-event result))
     (is (= [:start deadlock-event] seen)))))

(deftest execute-hands-over-all-replies-to-an-event-in-one-submission
  (let [submitted :pavlov/external-events-submitted
        ;; Records each submission it sees, and ends the program with them
        ;; once the last reply is selected.
        recorder (b/step
                  (fn [submissions e]
                    (cond (nil? e)
                          [[] {:wait-on #{submitted :r2}}]

                          (= :r2 e)
                          [submissions {:request #{{:type :done
                                                    :terminal true
                                                    :submissions submissions}}}]

                          :else
                          [(conj submissions e) {:wait-on #{submitted :r2}}])))
        !seen (atom [])
        stopped (bpe/execute!
                 [[:recorder recorder]
                  [:start (b/bids [{:request #{:start}}])]]
                 {:kill-after 1000
                  :subscribers
                  {:first (fn [e _] (when (= :start e) {:event :r1}))
                   :second (fn [e _] (when (= :start e) {:event :r2}))
                   :log (fn [e _] (swap! !seen conj e) nil)}})
        check (fn [result]
                (is (= [{:type submitted :events [:r1 :r2]}]
                       (:submissions result)))
                (is (= [:start :r1 :r2] (pop @!seen))))]
    #?(:clj (check (deref stopped 2000 ::timeout))
       :cljs (async done
               (.then stopped
                      (fn [result]
                        (check result)
                        (done)))))))
