(ns tech.thomascothran.pavlov
  (:require [tech.thomascothran.pavlov.proto :as proto]))

(extend-protocol proto/Bid
  #?(:clj clojure.lang.PersistentVector
     :cljs PersistentVector)
  (request [this] (first this))
  (wait [this] (second this))
  (block [this] (when (<= 3 (count this)) (nth this 2))))

(defn next-state
  ;; TODO you are going to need the last event
  ;; and the blocked threads, and you're going
  ;; to need to return the next event and the
  ;; parked threads

  ;; is ... this the program?

  ;; returns a triple of next event, active-bthreads, parked-bthreads

  [bthreads last-event]
  (let [bids (mapv #(proto/bid % last-event) bthreads)
        blocked (into #{} (mapcat proto/block) bids)
        requested (into []
                        (comp (mapcat proto/request)
                              (remove blocked))
                        bids)
        #_#_waiting (mapv second bids)]
    (first requested)))

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




