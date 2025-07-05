(ns tech.thomascothran.pavlov.bprogram
  (:require [tech.thomascothran.pavlov.bprogram.proto :as bp-proto]))

(defn stop!
  "Stop the bprogram, allowing all enqueued events to be processed.

  Returns a promise that is delivered when the bprogram has stopped."
  [bprogram]
  (bp-proto/stop! bprogram))

(defn kill!
  "Attempt to kill the program, ignoring enqueued events."
  [bprogram]
  (bp-proto/kill! bprogram))

(defn submit-event!
  "Submit an event to the bprogram."
  [bprogram event]
  (bp-proto/submit-event! bprogram event))

(defn subscribe!
  "Dynamically add a subscriber, named `k`, to the bprogram.

  `f` will be applied to each event and a map of the bthreads to their bids.

  Generally, the bthread->bid map will only be used for debugging."
  [bprogram k f]
  (bp-proto/subscribe! bprogram k f))

(defn bthread->bids
  "Get a map of the bthreads to their current bids"
  [bprogram]
  (bp-proto/bthread->bids bprogram))
