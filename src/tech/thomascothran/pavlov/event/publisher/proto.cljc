(ns tech.thomascothran.pavlov.event.publisher.proto)

(defprotocol Publisher
  :extend-via-metadata true
  (start! [this])
  (stop! [this])
  (notify! [this event bthread->bid])
  (subscribe! [this key f])
  (listeners [this]))
