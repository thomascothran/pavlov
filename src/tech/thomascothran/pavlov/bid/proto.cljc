(ns tech.thomascothran.pavlov.bid.proto)

(defprotocol Bid
  :extend-via-metadata true
  (request [this]
    "Returns the requested events")
  (wait-on [this]
    "Returns the events to wait on")
  (block [this]
    "Return a collection of events to block"))

