(ns tech.thomascothran.pavlov.bthread.proto)

(defprotocol BThread
  (bid [this last-event])
  (state [this])
  (set-state [this serialized]))
