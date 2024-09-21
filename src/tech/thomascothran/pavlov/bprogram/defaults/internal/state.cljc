(ns tech.thomascothran.pavlov.bprogram.defaults.internal.state
  (:require [tech.thomascothran.pavlov.bprogram.proto
             :refer [BidCollector] :as bprogram]
            [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.bthread.proto :as bthread]))

(defn- bid-reducer
  [bthreads event]
  (reduce (fn [acc bthread]
            (let [bid (bthread/bid bthread event)
                  priority (bthread/priority bthread)]
              (assoc acc bthread
                     {:request (bid/request bid)
                      :wait-on (bid/wait-on bid)
                      :block   (bid/block bid)
                      :priority priority})))
          {}
          bthreads))

(def bid-collector
  (reify BidCollector
    (collect [_ bthreads event]
      (bid-reducer bthreads event))))

(defn new-waits
  [bthread->bid]
  (reduce (fn [new-waits curr]
            (let [bthread (first curr)
                  bid     (second curr)
                  waits   (reduce into #{} [(bid/wait-on bid)
                                            (bid/request bid)])]
              (reduce (fn [acc event-name]
                        (update acc event-name #(into #{bthread} %)))
                      new-waits
                      waits)))
          {}
          bthread->bid))

(defn remove-old-waits
  [bthread-registry event-type]
  (let [bthreads (get bthread-registry event-type)
        remove-activated-bthreads #(into #{} (remove bthreads) %)]
    (if (seq bthreads)
      (-> bthread-registry
          (dissoc event-type)
          (update-vals remove-activated-bthreads))
      bthread-registry)))

(defn next-state
  ([event->bthread]
   {:event {:type :pavlov/init-event}
    :event->bthread {:pavlov/init-event (into #{} event->bthread)}})
  ([event->bthread event]
   (let [event-type (:type event)
         bthreads (get event->bthread event-type)
         bthread->bid (bprogram/collect bid-collector bthreads event)
         bids (sort-by :priority > (vals bthread->bid))
         blocked  (into #{} (mapcat :block) bids)
         requested (into [] (comp (mapcat :request)
                                  (remove blocked))
                         bids)
         next-event-type (first requested)
         waits (new-waits bthread->bid)
         new-event->handlers
         (merge-with into
                     (remove-old-waits event->bthread event-type)
                     waits)]

     (tap> [:next-state {:event event
                         :bids bids
                         :blocked blocked
                         :requested requested
                         :next-event-type next-event-type
                         :event->bthread event->bthread
                         :bthread->bid bthread->bid
                         :waits waits}])
     {:event (when next-event-type {:type next-event-type})
      :event->bthread new-event->handlers})))

