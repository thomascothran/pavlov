(ns tech.thomascothran.pavlov.bthread
  (:refer-clojure :exclude [seq reduce name])
  (:require [tech.thomascothran.pavlov.bthread.proto :as proto]))

(defn bid
  ([bthread event]
   (some-> (proto/bid bthread event)
           (vary-meta assoc :pavlov/bthread bthread))))

(defn state
  [bthread]
  (proto/state bthread))

(defn set-state
  [bthread serialized]
  (proto/set-state bthread serialized))

(defn bids
  "Make a bthread from a finite sequence of bids.

  The sequence *will* be fully realized

  Items in the sequence must be bthreads.

  If nil is received, the bthread stops."
  ([xs] (bids nil xs))
  ([name-prefix xs]
   (let [xs' (volatile! xs)]
     (reify proto/BThread
       (state [_] xs')
       (set-state [_ serialized] serialized)
       (bid [_ event]
         (when-let [x (first @xs')]
           (let [bid' (bid x event)]
             (vreset! xs' (rest @xs'))
             bid')))))))

(defn step
  "Create bthread with a step function.

  The `step-name` *must* be globally unique within
  a bprogram. If it is not globally unique you will
  see unpredictable behavior.

  A step function is:
  - Pure (has no side effects)
  - Takes (current state, event)
  - Returns (new state, bid)
 "
  ([step-name f] (step step-name f {}))
  ([step-name f _opts]
   (let [state (volatile! nil)]

     (reify proto/BThread
       (state [_] @state)
       (set-state [_ serialized] (vreset! state serialized))
       (bid [_ event]
         (let [result (f @state event)
               next-state (first result)
               bid (second result)]
           (vreset! state next-state)
           bid))))))

(defn reprise
  ([x] (reprise :forever x))
  ([n x]
   (let [repeate-forever? (or (nil? n) (= n :forever))
         step-fn
         (fn [invocations _]
           (let [invocations' (or invocations 1)]
             (if (and (not repeate-forever?)
                      (< n invocations'))
               [(inc invocations') nil]
               [(inc invocations') x])))]
     (step [::reprise n x] step-fn))))

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
  ([bids] (interlace nil bids))
  ([name-prefix bids]
   (let [bthread-name [(or name-prefix ::interpolate)]
         bthread-count (count bids)
         step-fn (fn [idx event]
                   (let [idx' (or idx 0)
                         active-bthread (nth bids idx')
                         next-idx (if (= (inc idx') bthread-count) 0 (inc idx'))
                         current-bid (bid active-bthread event)]
                     [next-idx current-bid]))]
     (step bthread-name step-fn))))
