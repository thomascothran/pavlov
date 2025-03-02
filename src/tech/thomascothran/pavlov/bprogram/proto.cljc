(ns tech.thomascothran.pavlov.bprogram.proto
  (:refer-clojure :exclude [pop conj]))

(defprotocol BProgram
  (stop! [this])
  (stopped [this])
  (kill! [this])
  (submit-event! [this event])
  (subscribe! [this k f]))

(defprotocol BProgramQueue
  (conj [this event])
  (pop [this]))
