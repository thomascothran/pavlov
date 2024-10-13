(ns tech.thomascothran.pavlov.bprogram.proto
  (:refer-clojure :exclude [pop conj]))

(defprotocol BidCollector
  :extend-via-metadata true
  (collect [this activated-bthreads event-filter]
    "Given an event, collect all the bids"))

(defprotocol Handler
  :extend-via-metadata true
  (id [this] "Id for the handler")
  (handle [this event]))

(defprotocol BProgram
  :extend-via-metadata true
  (start! [this]
    "Initialize the program with the bthreads")
  (stop! [this]
    "Stop the program. Will wait for requests to settle, processing all events requested by previous bthread bids.")
  (kill! [this]
    "Kill the program if possible. Will not wait for pending events to clear.")
  (submit-event! [this event] "Handle an event")
  (attach-handlers! [this handlers]
    "Handlers receives all the events emitted by the bprogram")
  (out-queue [this]
    "Returns the out queue for events that have been emitted"))

(defprotocol BProgramQueue
  :extend-via-metadata true
  (conj [this event])
  (pop [this]))
