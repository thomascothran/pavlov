(ns tech.thomascothran.pavlov.bthread
  (:refer-clojure :exclude [seq])
  (:require [tech.thomascothran.pavlov.bthread.proto :as proto]))

(defn bid
  [bthread event]
  (proto/bid bthread event))

(defn seq
  "Make a bthread from a sequence. Items in the sequence
  must be bthreads. If nil is received, the bthread stops."
  [xs]
  (let [xs' (volatile! xs)]
    (reify proto/BThread
      (bid [_ event]
        (when-let [x (first @xs')]
          (let [bid' (bid x event)]
            (vreset! xs' (rest @xs'))
            bid'))))))

