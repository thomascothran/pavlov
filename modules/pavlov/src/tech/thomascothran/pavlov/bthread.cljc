(ns tech.thomascothran.pavlov.bthread
  "Behavioral threads (bthreads) are the fundamental building blocks of
   behavioral programs in Pavlov. Each bthread encapsulates a single,
   isolated unit of behavior that communicates with other bthreads
   exclusively through events.

   ## What is a Bthread?

   A bthread is a stateful component that:
   - Receives notifications about events
   - Returns *bids* describing what events to request, wait for, or block
   - Parks until events it cares about occur
   - Never directly communicates with other bthreads

   Bthreads are composed into behavioral programs (bprograms) which
   coordinate event selection and notification.

   ## Bid Maps

   Every bthread returns a bid map (or nil to terminate) See `tech.thomascothran.pavlov.bid.proto/Bid`.

   Maps implement the bid protocol. Bid maps contain:
   - `:request` - events this bthread would like to trigger
   - `:wait-on` - event types that should wake this bthread
   - `:block`   - event types to prevent while this bid is active
   - `:bthreads` - child bthreads to spawn, keyed by name

   ```clojure
   {:request #{{:type :account-created}}  ; request this event
    :wait-on #{:user-action}              ; wake me on user actions
    :block   #{:dangerous-operation}}     ; prevent this while active
   ```

   Spawn child bthreads by returning `:bthreads` on a bid:

   ```clojure
   {:request #{:start}
    :bthreads {:child (b/bids [{:wait-on #{:start}}
                               {:request #{{:type :done
                                            :terminal true}}}])}}
   ```

   ## Available Bthread Constructors

   Pavlov provides several constructors for common patterns:

   | Constructor   | Use Case                                    |
   |---------------|---------------------------------------------|
   | `bids`        | Finite sequence of scripted bids (supports dynamic fns) |
   | `on`          | React to exactly one event type             |
   | `after-all`   | Coordinate multiple prerequisites           |
   | `repeat`      | Repeat a bid n times or forever             |
   | `round-robin` | Cycle through bthreads in order             |
   | `thread`      | Declarative branching on event types (macro)|
   | `step`        | Full control with state management          |

   A plain map is also a valid bthread that always returns itself:
   ```clojure
   {:request #{:heartbeat}}  ; Always requests :heartbeat
   ```

   ## Choosing a Constructor

   **Use `bids`** for scripted sequences - the most common pattern. Items can
   be literal bid maps or functions that compute bids dynamically from events:
   ```clojure
   ;; Static sequence
   (b/bids [{:request #{:step-1}}
            {:request #{:step-2}}])

   ;; Dynamic - functions receive the event and return a bid
   (b/bids [{:wait-on #{:order/placed}}
            (fn [event]
              {:request #{{:type :order/confirm
                           :order-id (:order-id event)}}})])
   ```

   **Use `on`** when you need stateless reactions to a single event type:
   ```clojure
   (b/on :invoice/received
         (fn [event]
           {:request #{{:type :invoice/processed
                        :id (:invoice/id event)}}}))
   ```

   **Use `after-all`** when waiting for multiple prerequisites:
   ```clojure
   (b/after-all #{:payment/authorized :packing/completed}
                (fn [events] {:request #{{:type :order/ready}}}))
   ```

   **Use `repeat`** for repetitive behavior:
   ```clojure
   (b/repeat 3 {:request #{:ping}})  ; Request :ping 3 times
   (b/repeat {:request #{:heartbeat}}) ; Forever
   ```

   **Use `round-robin`** to cycle through behaviors:
   ```clojure
   (b/round-robin
     [{:block #{:cold-water} :wait-on #{:hot-water}}
      {:block #{:hot-water} :wait-on #{:cold-water}}])
   ```

   **Use `thread`** for complex branching with state:
   ```clojure
   (b/thread [state event]
     :pavlov/init
     [{:count 0} {:wait-on #{:increment :decrement}}]

     :increment
     [(update state :count inc) {:wait-on #{:increment :decrement}}]

     :decrement
     [(update state :count dec) {:wait-on #{:increment :decrement}}])
   ```

   **Use `step`** when you need full control:
   ```clojure
   (b/step (fn [state event]
             (if (nil? event)
               [0 {:wait-on #{:tick}}]
               [(inc state) {:wait-on #{:tick}}])))
   ```

   Generally, prefer the higher-level constructors instead of `thread` or `step`.

   ## Important Notes

   - Bthreads are stateful; create new instances via functions, rather than `def`
     + Functions that take bthreads will almost always mutate them
   - `notify!` is for REPL/testing only; bprograms call it internally
   - Errors in bthreads emit `:tech.thomascothran.pavlov.bthread/unhandled-step-fn-error`. You should catch your own errors and not rely on this
   - Return `nil` from a bid to terminate and deregister the bthread

   ## Example: Complete Workflow

   ```clojure
   (require '[tech.thomascothran.pavlov.bthread :as b])
   (require '[tech.thomascothran.pavlov.bprogram.ephemeral :as bpe])

   (defn make-order-workflow []
     (b/bids [{:wait-on #{:order/placed}}
              {:request #{{:type :payment/charge}}}
              {:wait-on #{:payment/success :payment/failure}}]))

   (defn make-shipping-trigger []
     (b/on :payment/success
           (fn [event]
             {:request #{{:type :shipping/initiate
                          :order-id (:order-id event)}}})))

   @(bpe/execute!
     [[:order-workflow (make-order-workflow)]
      [:shipping (make-shipping-trigger)]]
     {:kill-after 100})
   ```

   ## Related namespaces and functions
   - `tech.thomascothran.pavlov.graph/->lts`: create a graph representation of the state space of a group of bthreads
   - `tech.thomascothran.pavlov.nav` - programmatically navigate bthreads with clojure's `nav`
   - `tech.thomascothran.pavlov.bprogram.ephemeral` for running bprograms.
   - `tech.thomascothran.pavlov.event. Do not assume events are simple maps - use the `event/type` function.
   - `tech.thomascothran.pavlov.model.check` for model checking bthreads and bprograms"

  (:refer-clojure :exclude [repeat])
  (:require [tech.thomascothran.pavlov.bthread.proto :as proto]
            [tech.thomascothran.pavlov.event.proto :as event-proto]
            [tech.thomascothran.pavlov.defaults])
  #?(:cljs (:require-macros [tech.thomascothran.pavlov.bthread])))

(defn notify!
  "Notify a bthread of an event and receive its next bid.

  Returns the bid map with `:pavlov/bthread` metadata attached, or nil
  if the bthread has terminated.

  NOTE: This is primarily for REPL exploration and testing. In production,
  the bprogram handles notification automatically.

  Example:
  ```clojure
  (def my-bthread (b/bids [{:request #{:a}} {:request #{:b}}]))
  (b/notify! my-bthread nil)        ;=> {:request #{:a}}
  (b/notify! my-bthread {:type :a}) ;=> {:request #{:b}}
  (b/notify! my-bthread {:type :b}) ;=> nil
  ```"
  [bthread event]
  (some-> (proto/notify! bthread event)
          (vary-meta assoc :pavlov/bthread bthread)))

(defn state
  "Get the current internal state of a bthread.

  Useful for serialization and debugging. The structure of the state
  depends on how the bthread was constructed.

  Example:
  ```clojure
  (def counter (b/step (fn [s _] [(inc (or s 0)) {:wait-on #{:tick}}])))
  (b/notify! counter nil)
  (b/state counter) ;=> 1
  ```"
  [bthread]
  (proto/state bthread))

(defn set-state
  "Restore a bthread's internal state from a serialized value.

  Used for deserialization and state restoration in durable programs.
  Returns the serialized state.

  Example:
  ```clojure
  (defn make-counter
    []
    (b/step (fn [s _] [(inc (or s 0)) {:wait-on #{:tick}}])))

  (let [counter (make-counter)]
    (b/notify! counter nil)
    (b/set-state counter 10)
    (b/state counter)) ;=> 10
  ```"
  [bthread serialized]
  (proto/set-state bthread serialized))

(defn bids
  "Create a bthread from a finite sequence of bids.

  Walks through `xs` in order, returning one bid per notification until
  the sequence is exhausted, then returns nil (terminating the bthread).

  Items in `xs` may be:
  - Bid maps (e.g., `{:request #{:event-a}}`)
  - Bthreads (including nested `bids` calls)
  - Functions `(fn [event] -> bid)` for dynamic computation

  The sequence is fully realized in memory.

  Example with literal bids:
  ```clojure
  (defn make-workflow
    []
    (b/bids [{:request #{:step-1}}
             {:request #{:step-2}}
             {:request #{:step-3}}]))
  (let [workflow (make-workflow)]
    (b/notify! workflow nil)           ;=> {:request #{:step-1}}
    (b/notify! workflow {:type :step-1})) ;=> {:request #{:step-2}}
  ```

  Example with dynamic function:

  ```clojure
  (defn make-dynamic-bids
    []
    (b/bids [{:wait-on #{:order/placed}}
             (fn [event]
               {:request #{{:type :confirm
                            :order-id (:order-id event)}}})]))
  ```"
  [xs]
  (let [xs' (volatile! xs)]
    (reify proto/BThread
      (state [_] @xs')
      (set-state [_ serialized] (vreset! xs' serialized))
      (label [_] @xs')
      (notify! [_ event]
        (when-let [x (first @xs')]
          (let [bid' (if (fn? x)
                       (x event)
                       (notify! x event))]
            (vreset! xs' (rest @xs'))
            bid'))))))

(defn- default-label
  [bthread]
  (proto/state bthread))

(defn step
  "Create a bthread from a step function for full state control.

  The step function signature is:
    `(fn [current-state event] -> [next-state bid])`

  On initialization, `event` is nil. Thereafter, the bthread is only
  notified of events it requested or waited on.

  The step function should be pure (no side effects). If it throws,
  an error event is emitted that terminates the program.

  Options:
  - `:label` - a function `(fn [bthread] -> label)` for debugging

  Example:
  ```clojure
  (defn make-counter
    []
    (b/step (fn [count event]
              (cond
                (nil? event)  [0 {:wait-on #{:tick}}]
                (< count 2)   [(inc count) {:wait-on #{:tick}}]
                :else         [(inc count) {:request #{{:type :done}}}]))))

  (let [counter (make-counter)]
    (b/notify! counter nil)            ;=> {:wait-on #{:tick}}
    (b/notify! counter {:type :tick})  ;=> {:wait-on #{:tick}}
    (b/notify! counter {:type :tick})  ;=> {:wait-on #{:tick}}
    (b/notify! counter {:type :tick})) ;=> {:request #{{:type :done}}}

  Prefer higher level functions to this one when possible.

  The `state` is also used by the model checker when building an execution graph.
  ```"
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
                (let [error-event-type ::unhandled-step-fn-error]
                  (when (and event
                             (not= error-event-type
                                   (event-proto/type event)))
                    {:request #{{:type error-event-type
                                 :event event
                                 :error e
                                 :invariant-violated true
                                 :terminal true}}})))))))))

(defn repeat
  "Create a bthread that returns the same bid repeatedly.

  With one argument, repeats forever:
  ```clojure
  (b/repeat {:request #{:heartbeat}})  ; Never stops
  ```

  With two arguments, repeats `n` times then returns nil:
  ```clojure
  (def ping (b/repeat 3 {:request #{:ping}}))
  (b/notify! ping nil)           ;=> {:request #{:ping}}
  (b/notify! ping {:type :ping}) ;=> {:request #{:ping}}
  (b/notify! ping {:type :ping}) ;=> {:request #{:ping}}
  (b/notify! ping {:type :ping}) ;=> nil
  ```"
  ([x] (repeat nil x))
  ([n x]
   (let [repeat-forever? (nil? n)
         infinite-step-fn (fn [_ _] [:infinite x])
         finite-step-fn
         (fn [invocations _]
           (let [invocations' (or invocations 1)]
             (if (and (not repeat-forever?)
                      (< n invocations'))
               [(inc invocations') nil]
               [(inc invocations') x])))

         step-fn
         (if repeat-forever?
           infinite-step-fn
           finite-step-fn)]
     (step step-fn))))

(defn on
  "Create a bthread that reacts to exactly one event type.

  When `event-type` is selected, `f` is called with the event and should
  return a bid. The bthread automatically continues waiting on `event-type`
  after each invocation (merged with any `:wait-on` in the returned bid).

  `f` is only called when `event-type` occurs - even if `f` returns a bid
  that requests or waits on other events.

  Example:
  ```clojure
  (def on-invoice
    (b/on :invoice/received
          (fn [event]
            {:request #{{:type :invoice/processed
                         :id (:invoice/id event)}}})))

  (b/notify! on-invoice nil)
  ;=> {:wait-on #{:invoice/received}}

  (b/notify! on-invoice {:type :invoice/received :invoice/id 42})
  ;=> {:request #{{:type :invoice/processed :id 42}}
  ;    :wait-on #{:invoice/received}}
  ```"
  [event-type f]
  (step (fn [_prev-state event]
          (if-not (and event
                       (= event-type (event-proto/type event)))
            [:initialized {:wait-on #{event-type}}] ;; initialize
            (let [bid (f event)
                  wait-on (->> (get event :wait-on #{})
                               (into #{event-type}))]
              [:initialized (assoc bid :wait-on wait-on)])))))

(defn after-all
  "Create a bthread that waits for all specified events before proceeding.

  Waits until every event type in `event-types` has occurred (in any order),
  then calls `f` with a vector of all the collected events. After `f`'s bid
  is emitted, the bthread terminates.

  Perfect for coordinating prerequisites from independent sources.

  Parameters:
  - `event-types` - set of event types to wait for (must be a set)
  - `f` - function `(fn [events] -> bid)` receiving events in arrival order

  Example:
  ```clojure
  (def order-ready
    (b/after-all #{:payment/authorized :packing/completed}
                 (fn [events]
                   {:request #{{:type :order/ready
                                :order-id (->> events (keep :order-id) first)}}})))

  (b/notify! order-ready nil)
  ;=> {:wait-on #{:payment/authorized :packing/completed}}

  (b/notify! order-ready {:type :packing/completed :order-id 42})
  ;=> {:wait-on #{:payment/authorized :packing/completed}}

  (b/notify! order-ready {:type :payment/authorized :order-id 42})
  ;=> {:request #{{:type :order/ready :order-id 42}}}

  (b/notify! order-ready {:type :anything})
  ;=> nil  ; terminated
  ```"
  [event-types f]
  (assert (set? event-types))
  (step (fn [prev-state event]
          (let [done (get prev-state :done)
                previous-events (get prev-state :previous-events [])
                seen-event-types (into #{}
                                       (comp (filter identity)
                                             (map event-proto/type))
                                       (conj previous-events event))
                default-bid {:wait-on event-types}
                new-events (if event
                             (conj previous-events event)
                             previous-events)
                new-state (assoc prev-state :previous-events new-events)]
            (when-not done
              (if (= event-types seen-event-types)
                [(assoc new-state :done true) (f new-events)]
                [new-state default-bid]))))))

(defn round-robin
  "Create a bthread that cycles through sub-bthreads in order.

  On each notification, asks the next bthread in sequence for its bid.
  Cycles back to the first bthread after reaching the end.
  Terminates when any sub-bthread returns nil.

  Useful for alternating behaviors, like interleaving hot and cold water.

  Example:
  ```clojure
  (def alternator
    (b/round-robin
      [(b/repeat {:wait-on #{:cold-water} :block #{:hot-water}})
       (b/repeat {:wait-on #{:hot-water} :block #{:cold-water}})]))

  ;; First notification goes to first bthread
  (b/notify! alternator nil)
  ;=> {:wait-on #{:cold-water} :block #{:hot-water}}

  ;; Second notification goes to second bthread
  (b/notify! alternator {:type :cold-water})
  ;=> {:wait-on #{:hot-water} :block #{:cold-water}}

  ;; Third goes back to first bthread
  (b/notify! alternator {:type :hot-water})
  ;=> {:wait-on #{:cold-water} :block #{:hot-water}}
  ```"
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
