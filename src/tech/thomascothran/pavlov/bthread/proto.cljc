(ns tech.thomascothran.pavlov.bthread.proto)

(defprotocol BThread
  :extend-via-metadata true
  (bid [this last-event]
    "Returns a bid"))

