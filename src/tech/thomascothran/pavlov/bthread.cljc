(ns tech.thomascothran.pavlov.bthread
  (:refer-clojure :exclude [seq reduce])
  (:require [tech.thomascothran.pavlov.bthread.proto :as proto]
            [tech.thomascothran.pavlov.event.proto :as event-proto]
            [tech.thomascothran.pavlov.defaults]))

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
  [xs]
  (let [xs' (volatile! xs)]
    (reify proto/BThread
      (state [_] @xs')
      (set-state [_ serialized] (vreset! xs' serialized))
      (label [_] @xs')
      (bid [_ event]
        (when-let [x (first @xs')]
          (let [bid' (bid x event)]
            (vreset! xs' (rest @xs'))
            bid'))))))

(defn- default-label
  [bthread]
  (proto/state bthread))

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
  ([f] (step f nil))
  ([f opts]
   (let [state (volatile! nil)
         label-fn (get opts :label default-label)]
     (reify proto/BThread
       (state [_] @state)
       (set-state [_ serialized] (vreset! state serialized))
       (label [this] (label-fn this))
       (bid [_ event]
         (try (let [result (f @state event)
                    next-state (first result)
                    bid (second result)]
                (vreset! state next-state)
                bid)
              (catch #?(:clj Throwable :cljs) e
                {:request #{{:type ::unhandled-step-fn-error
                             :event event
                             :error e
                             :terminal true}}})))))))

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
     (step step-fn))))

(defn on-every
  "Run `f` always and only when the specified events occurs.

  `event-names` is a set of event names

  `f` is a function of an event to a bid.

  `f` is only invoked on an event in `event-names` - even if
  it returns a bid that requests or waits on other events."
  [event-names f]
  (step (fn [_prev-state event]
          (if-not (and event
                       (get event-names (event-proto/type event)))
            [:initialized {:wait-on event-names}] ;; initialize
            (let [bid (f event)
                  wait-on (->> (get event :wait-on #{})
                               (into event-names))]
              [:initialized (assoc bid :wait-on wait-on)])))))

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
  (interpolate [(b/seq [{:request #{:a}}
                        {:request #{:b}}])
                (b/seq [{:request #{1}}])])
  ```
  In response to a notification,  interpolate will return
  in order *three* bids, for events `:a`, `:b`, and `1`
  "
  [bthreads]
  (let [bthread-count (count bthreads)
        step-fn (fn [state event]
                  (let [idx (get state :idx 0)
                        active-bthread (nth bthreads idx)
                        next-idx (if (= (inc idx) bthread-count) 0 (inc idx))
                        current-bid (bid active-bthread event)]
                    [{:idx next-idx
                      :bid-states (mapv proto/state bthreads)} ;; helps w/lasso detection
                     current-bid]))]
    (step step-fn)))
