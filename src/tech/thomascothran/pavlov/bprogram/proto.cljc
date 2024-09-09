(ns tech.thomascothran.pavlov.bprogram.proto)

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
  (submit-event! [this event] "Handle an event")
  (attach-handlers! [this handlers]
    "Handlers receives all the events emitted by the bprogram"))


