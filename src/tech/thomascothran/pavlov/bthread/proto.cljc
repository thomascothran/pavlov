(ns tech.thomascothran.pavlov.bthread.proto)

(defprotocol BThread
  :extend-via-metadata true
  (bid [this] [this last-event] "initial bid")
  (priority [this]
    "Returns a numeric value for priority"))

