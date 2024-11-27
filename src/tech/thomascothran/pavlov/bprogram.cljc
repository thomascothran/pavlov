(ns tech.thomascothran.pavlov.bprogram
  (:require [tech.thomascothran.pavlov.bprogram.proto :as bprogram]
            [tech.thomascothran.pavlov.bprogram.internal :as bpi]))

(defn stop!
  "Stop the bprogram, allowing bthreads to finish requesting and processing events."
  [bprogram]
  (bprogram/stop! bprogram))

(defn kill!
  "Kill the bprogram. No guarantees that bthreads will finish requesting and processing events.

  Useful for deadlocks."
  [bprogram]
  (bprogram/kill! bprogram))

(defn subscribe!
  "Apply `f` to every event emitted from the bprogram.
  
  `f` is keyed under `k`. You can update `f` by calling `subscribe!` again with the same `k`."
  [bprogram k f]
  (bprogram/subscribe! bprogram k f))

(defn submit-event!
  "Submit an event to the behavioral program.
  
  Events are placed in a queue and are not processed synchronously."
  [bprogram event]
  (bprogram/submit-event! bprogram event))

(defn start!
  "Start the program with the given `bthreads`.
  
  Optionally, provide an `opts` map as the second argument.
  
  `opts` include:
  - `subscribers`: a map with the key designating the subscriber, and the value the function to call on each event."
  ([bthreads] (bpi/make-program! bthreads))
  ([bthreads opts] (bpi/make-program! bthreads opts)))
