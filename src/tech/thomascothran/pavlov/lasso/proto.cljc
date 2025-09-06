(ns tech.thomascothran.pavlov.lasso.proto
  (:refer-clojure :exclude [key]))

(defprotocol LassoDetector
  (begin! [this]
    "Called when the *inner chain* starts (before the first internal next-event).")

  (observe! [this key]
    "Observe one synchronization point (canonical key of the BP state).
  Returns nil if new; or a map when a repetition is detected.
  :period and :mu are filled when the algorithm can compute them (e.g. Brent).

  The `key` is the sequence of bthread names -> labels in priority order. ")

  (end! [this]
    "called when the inner chain ends"))
