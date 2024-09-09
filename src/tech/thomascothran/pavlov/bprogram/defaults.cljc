(ns tech.thomascothran.pavlov.bprogram.defaults
  (:require [tech.thomascothran.pavlov.bprogram.proto
             :refer [BidCollector] :as bprogram]
            [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.bthread.proto :as bthread]))

(defn- bid-reducer
  [bthreads event]
  (reduce (fn [acc bthread]
            (let [bid (bthread/bid bthread event)]
              (assoc acc bthread
                     {::request (bid/request bid)
                      ::wait-on (bid/wait-on bid)
                      ::block   (bid/block bid)})))
          {}
          bthreads))

(def bid-collector
  (reify BidCollector
    (collect [_ bthreads event]
      (bid-reducer bthreads event))))

(defn next-state
  ([bthreads]
   {::event {:type ::init-event}
    ::event->handlers {::init-event (into #{} bthreads)}})
  ([bthread-registry event]
   (let [bthreads (get bthread-registry (:type event))
         bids     (bprogram/collect bid-collector bthreads event)
         blocked  (into #{} (mapcat ::block) (vals bids))
         requested
         (into []
               (comp (mapcat ::request)
                     (remove blocked))
               (vals bids))]

     {::event (first requested)
      ::event->handlers {}})))

(defn make-program
  [bthreads]
  (let [!state (atom {::event->handlers {}
                      ::bthread-queue [] ;; bthreads triggered but not
                      ::handlers  {}     ;; run
                      ::bthreads bthreads
                      ::last-event nil})]
    (with-meta {:!state !state}
      {`bprogram/attach-handlers!
       (fn [_ handler]
         (swap! !state update ::handlers
                assoc (bprogram/id handler) handler))

       `bprogram/submit-event!
       (fn [this event]
         (doseq [handler (::handlers this)]
           (bprogram/handle handler event))
         (swap! !state ::last-event event))

       `bprogram/start!
       #(swap! !state merge (next-state bthreads)  %)})))
