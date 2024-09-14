(ns tech.thomascothran.pavlov.bthread
  (:refer-clojure :exclude [seq])
  (:require [tech.thomascothran.pavlov.bthread.proto :as proto]))

(defn bid
  [bthread event]
  (proto/bid bthread event))

(defn seq
  "Make a bthread from a sequence. Items in the sequence
  must be bthreads. If nil is received, the bthread stops."
  ([xs] (seq xs {:priority 0}))
  ([xs opts]
   (let [priority (get opts :priority)
         xs' (volatile! xs)]
     (reify proto/BThread
       (priority [_] priority)
       (bid [_ event]
         (when-let [x (first @xs')]
           (let [bid' (bid x event)]
             (vreset! xs' (rest @xs'))
             bid')))))))

