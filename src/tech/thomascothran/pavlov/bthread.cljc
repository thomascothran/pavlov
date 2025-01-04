(ns tech.thomascothran.pavlov.bthread
  (:refer-clojure :exclude [seq reduce name])
  (:require [tech.thomascothran.pavlov.bthread.proto :as proto]))

(defn name
  [bthread]
  (proto/name bthread))

(defn bid
  ([bthread event]
   (some-> (proto/bid bthread event)
           (vary-meta assoc :pavlov/bthread bthread))))

(defn priority
  [bthread]
  (proto/priority bthread))

(defn serialize
  [bthread]
  (proto/serialize bthread))

(defn deserialize
  [bthread serialized]
  (proto/deserialize bthread serialized))

(defn seq
  "Make a bthread from a finite sequence.

  The sequence will be fully realized
  
  Items in the sequence must be bthreads. 
  
  If nil is received, the bthread stops."
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

(defn step
  "Create bthread with a step function.
  
  A step function is:
  - Pure (has no side effects)
  - Takes (current state, event)
  - Returns [new state, bid]
 "
  ([f] (step f {:priority 0}))
  ([f {:keys [name priority]}]
   (let [state (volatile! nil)]
     (reify proto/BThread
       (name [this] (or name (str this)))
       (priority [_] priority)
       (serialize [_] @state)
       (deserialize [_ serialized] (vreset! state serialized))
       (bid [_ event]
         (let [result (f @state event)
               next-state (first result)
               bid (second result)]
           (vreset! state next-state)
           bid))))))

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
       (bid [_ event]
         (let [bid (f @acc event)]
           (vreset! acc bid)
           bid))))))


