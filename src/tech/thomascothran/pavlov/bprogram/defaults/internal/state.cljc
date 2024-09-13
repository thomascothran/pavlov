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
  ([bthreads]
   {:event {:type :pavlov/init-event}
    :active-bthreads #{bthreads}
    :event->handlers {:pavlov/init-event (into #{} bthreads)}})
  ([bthread-registry event]
   (let [event-type (:type event)
         bthreads (get bthread-registry event-type)
         bthread->bid (bprogram/collect bid-collector bthreads event)
         bids (vals bthread->bid)
         blocked  (into #{} (mapcat :block) bids)
         requested
         (into []
               (comp (mapcat :request)
                     (remove blocked))
               bids)
         next-event-type (first requested)
         waits (new-waits bthread->bid)
         new-event->handlers
         (merge-with into
                     (remove-old-waits bthread-registry event-type)
                     waits)]

     {:event (when next-event-type {:type next-event-type})
      :event->handlers new-event->handlers})))

