(ns tech.thomascothran.pavlov.bthread
  (:refer-clojure :exclude [seq reduce name])
  (:require [tech.thomascothran.pavlov.bthread.proto :as proto]))

(defn name
  [bthread]
  (proto/name bthread))

(defn bid
  ([bthread]
   (some-> (proto/bid bthread)
           (vary-meta assoc :pavlov/bthread bthread)))
  ([bthread event]
   (some-> (proto/bid bthread event)
           (vary-meta assoc :pavlov/bthread bthread))))

(defn priority
  [bthread]
  (proto/priority bthread))

(defn seq
  "Make a bthread from a sequence. Items in the sequence
  must be bthreads. If nil is received, the bthread stops."
  ([xs] (seq xs {:priority 0}))
  ([xs opts]
   (let [priority (get opts :priority)
         xs' (volatile! xs)]
     (reify proto/BThread
       (priority [_] priority)
       (bid
         [_]
         (when-let [x (first @xs')]
           (let [bid' (bid x)]
             (vreset! xs' (rest @xs'))
             bid')))
       (bid [_ event]
         (when-let [x (first @xs')]
           (let [bid' (bid x event)]
             (vreset! xs' (rest @xs'))
             bid')))))))

(defn reduce
  "Make a bthread from a reducing function.
  
  The function takes the accumulated value and the
  new event.
  
  The accumulated value is the previous bid, which can
  have any extra information added to it.
  
  Example
  -------
  ```
  (def bthread
    (b/reduce (fn [{:keys [times-called]} _]
                (when-not (= times-called 3)
                  {:request #{:more}
                   :times-called (inc times-called)}))
              {:times-called 0}))
  ```"
  ([f] (reduce f nil))
  ([f init] (reduce f init {:priority 0}))
  ([f init opts]
   (let [priority (get opts :priority)
         acc (volatile! init)]
     (reify proto/BThread
       (priority [_] priority)
       (bid [_]
         (let [bid (f @acc nil)]
           (vreset! acc bid)
           bid))
       (bid [_ event]
         (let [bid (f @acc event)]
           (vreset! acc bid)
           bid))))))


