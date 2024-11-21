(ns tech.thomascothran.pavlov.bprogram.proto
  (:refer-clojure :exclude [pop conj]))

(defprotocol BProgram
  :extend-via-metadata true
  (stop! [this]
    "Stop the program. Will wait for requests to settle, processing all events requested by previous bthread bids.")
  (kill! [this]
    "Kill the program if possible. Will not wait for pending events to clear.")
  (submit-event! [this event] "Handle an event")
  (listen! [this k f]
    "Call `f` on each emitted event. `k` uniquely identifies
    the subscription."))

(defprotocol BProgramQueue
  :extend-via-metadata true
  (conj [this event])
  (pop [this]))
