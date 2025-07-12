(ns tech.thomascothran.pavlov.bthread.proto)

(defprotocol BThread
  (bid [this last-event])
  (serialize [this])
  (deserialize [this serialized]))
