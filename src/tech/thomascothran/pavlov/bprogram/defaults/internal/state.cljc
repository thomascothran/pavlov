(ns tech.thomascothran.pavlov.bprogram.defaults.internal.state
  (:require [tech.thomascothran.pavlov.bprogram.proto
             :refer [BidCollector] :as bprogram]
            [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.bthread.proto :as bthread]))

(defn- bid-reducer
  [bthreads event]
  (reduce (fn [acc bthread]
            (let [bid (bthread/bid bthread event)]
              (assoc acc bthread
                     {:request (bid/request bid)
                      :wait-on (bid/wait-on bid)
                      :block   (bid/block bid)})))
          {}
          bthreads))

(def bid-collector
  (reify BidCollector
    (collect [_ bthreads event]
      (bid-reducer bthreads event))))

(defn- waiting
  "return the bids that are not waiting on something"
  [bthread->bid event-type]
  (into #{} (comp (map second)
                  (remove (comp nil? bid/wait-on))
                  (remove (comp (partial = #{event-type}) bid/wait-on)))
        bthread->bid))

(defn next-state
  ([bthreads]
   {:event {:type :pavlov/init-event}
    :event->handlers {:pavlov/init-event (into #{} bthreads)}})
  ([bthread-registry event]
   (let [event-type (:type event)
         bthreads (get bthread-registry event-type)
         bids     (bprogram/collect bid-collector bthreads event)
         blocked  (into #{} (mapcat :block) (vals bids))
         requested
         (into []
               (comp (mapcat :request)
                     (remove blocked))
               (vals bids))
         next-event-type (first requested)]

     {:event (when next-event-type {:type next-event-type})
      :event->handlers (dissoc bthread-registry event-type)})))

;; if the thread is waiting on the event and it is not waiting
;; on any other event, then it is released... what does this mean?

;; if the thread is waiting on this and other events, it's waits
;; on others are removed

