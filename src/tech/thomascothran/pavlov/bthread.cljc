(ns tech.thomascothran.pavlov.bthread
  (:refer-clojure :exclude [repeat])
  (:require [tech.thomascothran.pavlov.bthread.proto :as proto]
            [tech.thomascothran.pavlov.event.proto :as event-proto]
            [tech.thomascothran.pavlov.defaults]))

(defn notify!
  [bthread event]
  (some-> (proto/notify! bthread event)
          (vary-meta assoc :pavlov/bthread bthread)))

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
      (notify! [_ event]
        (when-let [x (first @xs')]
          (let [bid' (notify! x event)]
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
       (notify! [_ event]
         (try (let [result (f @state event)
                    next-state (first result)
                    bid (second result)]
                (vreset! state next-state)
                bid)
              (catch #?(:clj Throwable :cljs :default) e
                {:request #{{:type ::unhandled-step-fn-error
                             :event event
                             :error e
                             :terminal true}}})))))))

(defn repeat
  ([x] (repeat nil x))
  ([n x]
   (let [repeat-forever? (nil? n)
         step-fn
         (fn [invocations _]
           (let [invocations' (or invocations 1)]
             (if (and (not repeat-forever?)
                      (< n invocations'))
               [(inc invocations') nil]
               [(inc invocations') x])))]
     (step step-fn))))

(defn on
  "Run `f` always and only when the specified events occurs.

  `event-names` is a set of event names

  `f` is a function of an event to a bid.

  `f` is only invoked on an event in `event-names` - even if
  it returns a bid that requests or waits on other events."
  [event-type f]
  (step (fn [_prev-state event]
          (if-not (and event
                       (= event-type (event-proto/type event)))
            [:initialized {:wait-on #{event-type}}] ;; initialize
            (let [bid (f event)
                  wait-on (->> (get event :wait-on #{})
                               (into #{event-type}))]
              [:initialized (assoc bid :wait-on wait-on)])))))

(defn round-robin
  "Ask bthreads for bids in round-robin fashion
  in order, until one bthread returns a bid of `nil`."
  [bthreads]
  (let [bthread-count (count bthreads)
        step-fn (fn [state event]
                  (let [idx (get state :idx 0)
                        active-bthread (nth bthreads idx)
                        next-idx (if (= (inc idx) bthread-count) 0 (inc idx))
                        current-bid (notify! active-bthread event)]
                    [{:idx next-idx
                      :bid-states (mapv proto/state bthreads)} ;; helps w/lasso detection
                     current-bid]))]
    (step step-fn)))

(defn- thread*
  [forms]
  (let [binding-vector (first forms)
        _ (assert (= 2 (count binding-vector))
                  "Only two arguments, for previous state and the event")
        event (second binding-vector)
        init-key (second forms)
        init-case (nth forms 2)
        _ (assert (= init-key :pavlov/init)
                  "Must provide :pavlov/init case")
        cases (->> (rest forms)
                   (drop 2)
                   (partition 2)
                   (mapcat (fn [[fst snd]]
                             [(into '() (if (keyword? fst)
                                          [fst] fst))
                              snd])))
        default-case (when (odd? (count (rest forms)))
                       (last (rest forms)))]
    `(step (fn ~binding-vector
             (let [event-type# (get ~event :type)]
               (case event-type#
                 nil ~init-case
                 ~@cases
                 ~default-case))))))

#?(:clj
   (defmacro thread
     "Create a bthread.

     When the bthread is notified with an event you specified,
     the corresponding form will be evaludated.

     Each form most return a tuple of `next-state`, `bid`.

     You must first handle initialization with `:pavlov/init`.

     Simple example
     --------------
     ```clojure
     (b/thread [prev-state event]
       ;; First, you *must* handle the initialization of the bthread
       :pavlov/init
       [{:event-a-count 0}       ;; tuple of next state
        {:wait-on #{:event-a}}]  ;; and bid

       ;; bthread will park until it is notified of `:event-a`
       :event-a  ;; when notified of `:event-a`, return the next
       [(update prev-state :event-a-count inc)
        {:request #{{:type :event-a-handled}}}])
     ```


     You may also pass a next-state, bid tuple in the last position.
     Analogous with `case`, this will be the default when the bthread
     is notified of an event and that event is not explicitly handled.


     Example with defaults
     --------
     ```clojure
     (b/thread [prev-state event]
       :pavlov/init         ;; <- always required in this position to initialize bthread
       [{:initialized true} ;; <- initialized bthread state
       {:wait-on #{:fire-missiles}}] ;; <- bid, wait until someone
                                     ;; wants to fire missiles

       :fire-missiles ;; when this event in this set occurs, execute form
       (let [result (missiles-api/fire!)] ;; do something
         [prev-state                      ;; return previous state and bid
         {:request #{{:type :missiles-fired
                       :result result}}}])

       ;; if bthread notified of any other event, then return the previous
       ;; state and this bid.
       [prev-state {:wait-on #{:fire-missiles}}])
     ```"
     {:clj-kondo/lint-as 'clojure.core/fn
      :style/indent [:block 1]}
     [& forms]
     (thread* forms)))

(comment
  (macroexpand-1
   '(thread [prev-state event]

      :pavlov/init
      [{:initialized true}
       {:wait-on #{:fire-missiles}}]

      #{:fire-missiles}
      (let [result (missiles-api/fire!)]
        [prev-state {:request #{{:type :missiles-fired
                                 :result result}}}])

      [prev-state {:wait-on #{:fire-missiles}}])))
