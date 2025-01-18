(ns tech.thomascothran.pavlov.bthread
  (:refer-clojure :exclude [seq reduce name])
  (:require [tech.thomascothran.pavlov.bthread.proto :as proto]))

(defn name
  "Name for a bthread that is globally unuque within a bprogram."
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

(defn bids
  "Make a bthread from a finite sequence of bids.

  The sequence *will* be fully realized
  
  Items in the sequence must be bthreads. 
  
  If nil is received, the bthread stops."
  ([xs] (bids xs {:priority 0}))
  ([xs opts]
   (let [priority (get opts :priority)
         xs' (volatile! xs)]
     (reify proto/BThread
       (name [_] [::seq (vec xs)])
       (priority [_] priority)
       (serialize [_] xs)
       (deserialize [_ serialized] serialized)
       (bid [_ event]
         (when-let [x (first @xs')]
           (let [bid' (bid x event)]
             (vreset! xs' (rest @xs'))
             bid')))))))

(defn step
  "Create bthread with a step function.

  The `step-name` must be globally unique within 
  a bprogram.
  
  A step function is:
  - Pure (has no side effects)
  - Takes (current state, event)
  - Returns (new state, bid)
 "
  ([step-name f] (step step-name f {:priority 0}))
  ([step-name f opts]
   (let [state (volatile! nil)
         priority (get opts :priority)]

     (reify proto/BThread
       (name [_] step-name)
       (priority [_] priority)
       (serialize [_] @state)
       (deserialize [_ serialized] (vreset! state serialized))
       (bid [_ event]
         (let [result (f @state event)
               next-state (first result)
               bid (second result)]
           (vreset! state next-state)
           bid))))))

(defn reprise
  ([x] (reprise :forever x {:priority 0}))
  ([n x] (reprise n x {:priority 0}))
  ([n x opts]
   (let [repeate-forever? (or (nil? n) (= n :forever))
         step-fn
         (fn [invocations _]
           (let [invocations' (or invocations 1)]
             (if (and (not repeate-forever?)
                      (< n invocations'))
               [(inc invocations') nil]
               [(inc invocations') x])))]
     (step [::reprise n] step-fn opts))))

(defn interlace
  "Ask bthreads for bids in round-robin fashion
  in order, until one bthread returns a bid of `nil`.
  
  This is different from `interleave`.
  
  With interleave:

  ```
  (interleave [:a :b] [1])
  ;; => [:a 1]
  ```

  However, with interpolate:

  ```
  (interpolate [(b/seq [{:request #{:a :b}}
                        {:request #{1}]))
  ;; interplate will return *three* bids, for
  ;; events `:a`, `:b`, and `1`
  ```

  
  "
  ([bthreads] (interlace bthreads {:priority 0}))
  ([bthreads opts]
   (let [priority (get opts :priority)
         bthread-name [::interpolate (mapv name bthreads)]
         bthread-count (count bthreads)
         step-fn (fn [idx event]
                   (let [idx' (or idx 0)
                         active-bthread (nth bthreads idx')
                         next-idx (if (= (inc idx') bthread-count) 0 (inc idx'))
                         current-bid (bid active-bthread event)]
                     (println "idx: " idx "\nbid: " current-bid)
                     [next-idx current-bid]))]
     (step bthread-name step-fn {:priority priority}))))


