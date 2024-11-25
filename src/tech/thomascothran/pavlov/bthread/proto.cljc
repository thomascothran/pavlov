(ns tech.thomascothran.pavlov.bthread.proto
  (:refer-clojure :exclude [name]))

(defprotocol BThread
  :extend-via-metadata true
  (name [this] "a unique, namespaced name for the bthread")
  (bid [this] [this last-event] "initial bid")
  (priority [this]
    "Returns a numeric value for priority"))

