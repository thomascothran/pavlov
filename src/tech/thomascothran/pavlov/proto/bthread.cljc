(ns tech.thomascothran.pavlov.proto.bthread)

(defprotocol BThread
  :extend-via-metadata true
  (handle-event [this event]
    "Returns a deferred Bid"))
