(ns tech.thomascothran.pavlov.bthread.proto
  (:refer-clojure :exclude [name]))

(defprotocol BThread
  (name [this])
  (bid [this last-event])
  (serialize [this])
  (deserialize [this serialized]))
