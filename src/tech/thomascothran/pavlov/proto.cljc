(ns tech.thomascothran.pavlov.proto)

(defprotocol BThread
  :extend-via-metadata true
  (bid [this last-event]
    "Returns a 3 tuple of:
         1. Requested events
         2. Events to wait on
         3. Events to block
         
        Each group is a collection of events."))

(defprotocol Bid
  :extend-via-metadata true
  (request [this]
    "Returns the requested events")
  (wait [this]
    "Returns a collection of events to wait on")
  (block [this]
    "Return a collection of events to block"))

