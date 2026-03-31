(ns tech.thomascothran.pavlov.web.dom.scheduler-test
  (:require [cljs.test :refer-macros [deftest is]]
            [tech.thomascothran.pavlov.web.dom.scheduler :as scheduler]))

(defn- make-fake-timeouts
  []
  (let [!scheduled (atom [])
        !cleared (atom [])
        !next-id (atom 0)
        set-timeout! (fn [f ms]
                       (let [token (keyword (str "timer-" (swap! !next-id inc)))]
                         (swap! !scheduled conj {:token token
                                                 :f f
                                                 :ms ms})
                         token))
        clear-timeout! (fn [token]
                         (swap! !cleared conj token))]
    {:!scheduled !scheduled
     :!cleared !cleared
     :set-timeout! set-timeout!
     :clear-timeout! clear-timeout!}))

(deftest make-event-scheduler-debounces-opted-in-events-via-injected-set-timeout
  (let [{:keys [!scheduled !cleared set-timeout! clear-timeout!]} (make-fake-timeouts)
        !translated (atom [])
        !submitted (atom [])
        native-event #js {:value "Buy milk"}
        matched-el #js {}
        context {:dom/event-name "input"
                 :matched-el matched-el
                 :pavlov-on-input :task-form/input-changed
                 :pavlov-input-debounce-ms "10"}
        event-scheduler! (scheduler/make-event-scheduler {:set-timeout! set-timeout!
                                                          :clear-timeout! clear-timeout!})
        translator (fn [event resolved-context]
                     (swap! !translated conj [event resolved-context])
                     {:type :dom/input
                      :dom/input {:value (.-value event)}})
        submit! (fn [event]
                  (swap! !submitted conj event))]
    (event-scheduler! {:native-event native-event
                       :context context
                       :translator translator
                       :submit! submit!})
    (is (= [] @!translated))
    (is (= [] @!submitted))
    (is (= [{:token :timer-1
             :f (:f (first @!scheduled))
             :ms 10}]
           @!scheduled))
    (is (fn? (:f (first @!scheduled))))
    (is (= [] @!cleared))))

(deftest make-event-scheduler-cancels-the-prior-debounce-timer-and-submits-the-latest-event-when-invoked
  (let [{:keys [!scheduled !cleared set-timeout! clear-timeout!]} (make-fake-timeouts)
        !translated (atom [])
        !submitted (atom [])
        matched-el #js {}
        context {:dom/event-name "input"
                 :matched-el matched-el
                 :pavlov-on-input :task-form/input-changed
                 :pavlov-input-debounce-ms "10"}
        first-event #js {:value "Buy"}
        second-event #js {:value "Buy milk"}
        event-scheduler! (scheduler/make-event-scheduler {:set-timeout! set-timeout!
                                                          :clear-timeout! clear-timeout!})
        translator (fn [event resolved-context]
                     (swap! !translated conj [event resolved-context])
                     {:type :dom/input
                      :dom/input {:value (.-value event)}})
        submit! (fn [event]
                  (swap! !submitted conj event))]
    (event-scheduler! {:native-event first-event
                       :context context
                       :translator translator
                       :submit! submit!})
    (event-scheduler! {:native-event second-event
                       :context context
                       :translator translator
                       :submit! submit!})
    (is (= [:timer-1]
           @!cleared))
    (is (= 2
           (count @!scheduled)))
    ((:f (second @!scheduled)))
    (is (= [[second-event context]]
           @!translated))
    (is (= [{:type :dom/input
             :dom/input {:value "Buy milk"}}]
           @!submitted))))

(deftest make-event-scheduler-throttles-opted-in-events-with-leading-edge-delivery
  (let [{:keys [!scheduled !cleared set-timeout! clear-timeout!]} (make-fake-timeouts)
        !translated (atom [])
        !submitted (atom [])
        matched-el #js {}
        context {:dom/event-name "input"
                 :matched-el matched-el
                 :pavlov-on-input :task-form/input-changed
                 :pavlov-input-throttle-ms "10"}
        first-event #js {:value "Buy"}
        second-event #js {:value "Buy milk"}
        third-event #js {:value "Buy milk now"}
        event-scheduler! (scheduler/make-event-scheduler {:set-timeout! set-timeout!
                                                          :clear-timeout! clear-timeout!})
        translator (fn [event resolved-context]
                     (swap! !translated conj [event resolved-context])
                     {:type :dom/input
                      :dom/input {:value (.-value event)}})
        submit! (fn [event]
                  (swap! !submitted conj event))]
    (event-scheduler! {:native-event first-event
                       :context context
                       :translator translator
                       :submit! submit!})
    (is (= [[first-event context]]
           @!translated))
    (is (= [{:type :dom/input
             :dom/input {:value "Buy"}}]
           @!submitted))
    (is (= [{:token :timer-1
             :f (:f (first @!scheduled))
             :ms 10}]
           @!scheduled))
    (event-scheduler! {:native-event second-event
                       :context context
                       :translator translator
                       :submit! submit!})
    (is (= [[first-event context]]
           @!translated))
    (is (= [{:type :dom/input
             :dom/input {:value "Buy"}}]
           @!submitted))
    (is (= [] @!cleared))
    ((:f (first @!scheduled)))
    (event-scheduler! {:native-event third-event
                       :context context
                       :translator translator
                       :submit! submit!})
    (is (= [[first-event context]
            [third-event context]]
           @!translated))
    (is (= [{:type :dom/input
             :dom/input {:value "Buy"}}
            {:type :dom/input
             :dom/input {:value "Buy milk now"}}]
           @!submitted))
    (is (= 2
           (count @!scheduled)))))
