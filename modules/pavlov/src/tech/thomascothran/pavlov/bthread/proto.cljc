(ns tech.thomascothran.pavlov.bthread.proto)

(defprotocol BThread
  (notify! [this last-event])
  (state [this])
  (label [this])
  (set-state [this serialized]))
