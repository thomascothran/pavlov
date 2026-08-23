(ns tech.thomascothran.pavlov.bprogram.notification-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [tech.thomascothran.pavlov.event.defaults]
            [tech.thomascothran.pavlov.bid.defaults]
            [tech.thomascothran.pavlov.bthread.defaults]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram.state :as state]
            [tech.thomascothran.pavlov.bprogram.notification :as notification]))

(deftest indexing-a-bid-preserves-existing-event-members
  (is (= {:requests {:go #{:existing :new}}}
         (notification/index-bid-events
          {:requests {:go #{:existing}}}
          :new
          {:request [:go]}
          :requests))))

(deftest bthread-requesting-and-waiting-on-an-event-is-notified-once
  (let [worker (b/step
                (fn [notification-count event]
                  (if (nil? event)
                    [0 {:request #{{:type :go}}
                        :wait-on #{:go}}]
                    [(inc notification-count)
                     {:wait-on #{:done}}])))
        bprogram-state (state/init {:worker worker})]
    (notification/notify-bthreads! bprogram-state {:type :go})

    (is (= 1 (b/state worker))
        "A bthread should be notified once when it both requests and waits on the selected event.")))
