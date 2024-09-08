(ns tech.thomascothran.pavlov
  (:require [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.bthread.proto :as b]))

(defn- bid-reducer
  [bthreads event]
  (reduce (fn [acc bthread]
            (let [bid (b/bid bthread event)]
              (assoc acc bthread
                     {::request (bid/request bid)
                      ::wait-on (bid/wait-on bid)
                      ::block   (bid/block bid)})))
          {}
          bthreads))

(defn- new-registry
  "Takes a map of bthreads -> their bids "
  [bids]
  (reduce (fn [acc [bthread {:keys [request
                                    wait-on]
                             :as bids}]] ;; add bthread to all bids
            (-> acc
                (update)))))

(defn next-state
  ;; TODO you are going to need the last event
  ;; and the blocked threads, and you're going
  ;; to need to return the next event and the
  ;; parked threads

  ;; is ... this the program?

  ;; single arity is startup.
  ;; listener -> map of event type -> bthreads.
  ([bthreads]
   {::event {:type ::init-event}
    ::registry {::init-event (into #{} bthreads)}})
  ([bthread-registry event]
   (let [bthreads (get bthread-registry (:type event))
         bids (bid-reducer bthreads event)
         _ (def bids bids)
         blocked (into #{} (mapcat ::block) (vals bids))
         requested
         (into []
               (comp (mapcat ::request)
                     (remove blocked))
               (vals bids))]

     {::event (first requested)
      ::bthread-registry {}})))

#_(defprotocol Program
    :extend-via-metadata true
    (start! [this] "Start the program")
    (enqueue [this event] "Enqueue an event")
    (listen [this listener]
      "Listener is a function that is called on each emitted event"))

#_(defn program
    "Make a program"
    [bthreads]
    (let [event-queue (atom [])
          out-queue (atom [])] ;; add watcher and execute bthreads?
      (with-meta {:bthreads bthreads
                  :event-queue event-queue
                  :out-queue out-queue}
        {`enqueue (fn [_ event]
                    (swap! event-queue conj event))
         `listen (fn [_ _]
                   (throw (ex-info "TODO" {})))
         `start! (fn [program]
                   (add-watch (:event-queue program)
                              ..))})))




