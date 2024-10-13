(ns tech.thomascothran.pavlov.event.proto
  (:refer-clojure :exclude [type]))

(defprotocol Event
  (type [event])
  (terminal? [event]))
