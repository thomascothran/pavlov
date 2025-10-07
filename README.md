# Pavlov: Behavioral Programming for Clojure

*Status: alpha*

[![Clojars Project](https://img.shields.io/clojars/v/tech.thomascothran/pavlov.svg)](https://clojars.org/tech.thomascothran/pavlov)

Pavlov is an opinionated [behavioral programming](https://cacm.acm.org/research/behavioral-programming/#R26) library for Clojure(Script). Behavioral programming was invented by David Harel, who also invented statecharts. It has a solid theoretical foundation, and is extremely simple by design.

Behavioral programming uses a basic unit called a bthread (for "behavioral thread"). Bthreads encapsulate behaviors.  They park until events to which they are subscribed occur. Bthreads communicate exclusively via queues.

Bthreads are composed together into behavioral programs. These can be long-running in event-driven systems. Or they can be invoked as a synchronous function. The bprogram's queue can be treated as an implementation detail - and a behavioral program can be called just like a synchronous function.

Pavlov's implementation of behavioral programming:

- Is navigable via `nav`. Use portal for point and click navigation of the branching execution paths of a program. As you navigate, the program state automatically resets.
- Comes with model checking (for free). Use a model checker to drive development. Given a set of behaviors, specify the valid final events and safety properties, and the model checker will walk you (or an LLM) through writing the application.
- Enables durable programs - programs that serialize to disk.

## Design Goals

1. *Zero dependencies*.
2. *Swappable implementations*. Bthreads and bprograms are open for extension and modification.
3. *BYO parallelization*. Bthreads can run in parallel and you should choose how. Bring your own thread pool, or use core.async.
4. *CLJS support*.

## Bthreads

In BP, a unit of application behavior is a bthread. Bthreads can run in parallel and park until an event they are interested in occurs. Bthreads are assembled into a pub-sub system—a bprogram. Each bprogram can:

1. Request events
2. Wait on events
3. Block events

Bprograms do this by returning a bid when they are either initialized or notified of an event to which they are subscribed.

Events may come from an external process. This can be anything: not only a bid from a bthread, but a user action in a UI, an event from a Kafka queue, an HTTP request, etc.

When an event occurs, all bthreads that have either requested that event or are waiting on that event submit their next bid.

For a deeper introduction to the lifecycle of bthreads and how bids work, see [What is a bthread?](./doc/what-is-a-bthread.md). To explore groups of bthreads interactively, see [Navigating Behavioral Programs](./doc/navigating-bprograms.md).

## Bprograms

The bprogram will select the next event based on the bids. Any event that is blocked by any bthread will never be selected.

This means bthreads can block events requested by other bthreads.

The main purpose of a behavioral program is to select the next event, and notify all bthreads subscribed to that event type. Bthreads only subscribe to events if they request them or are waiting on them.

### Event Selection Rules

The bprogram will select an event according to the following rules:

1. Find the highest priority bthread which has requested at least one unblocked event
2. Select the highest priority event requested by that bthread

Bprograms use clojure's collection semantics to determine priority order. Unordered collections (maps for bthreads and sets for requested events) have a non-deterministic priority. In most cases this is fine.

However, ordered collections (a sequence of bthread name, bthread pairs for bthreads; or a vector for requested events) can be used to impose a deterministic priority order.

## Internal and External Events

All events requested by bthreads will be handled before external events.

This means that when an event is submitted to the bprogram, bids will be requested and any events they request will be processed repeatedly until there are no outstanding requested events.

At that point in time, the next external event will be processed.

## Simple Example

Let's suppose we have an industrial process which should have the following behaviors:

1. 3 units of hot water should be added
2. 3 units of cold water should be added
3. The addition of hot and cold water should be interleaved to control the overall temperature.

```clojure
(ns water-controls.app
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]))

(defn log-step [event program]
  (println "selected" event)
  (println "bids" (bp/bthread->bids program))
  (println "---"))

@(bpe/execute!
   [[:add-hot (b/repeat 3 {:request #{:add-hot-water}})]
    [:add-cold (b/repeat 3 {:request #{:add-cold-water}})]
    [:alternator
     (b/round-robin
       [(b/repeat {:wait-on #{:add-cold-water}
                   :block #{:add-hot-water}})
        (b/repeat {:wait-on #{:add-hot-water}
                   :block #{:add-cold-water}})])]]
   {:subscribers {:logger log-step}
    :kill-after 50}) ;; if program has not exited by 50 ms, kill it
;; => prints each selected event with its active bids
;; => {:type :tech.thomascothran.pavlov.bprogram.ephemeral/deadlock,
;;     :terminal true}
```

## Creating bthreads

Bthreads are stateful. They can run in parallel and be parked when they are waiting on events.

The bid a bthread produces can request events, wait on events, or block events in other bthreads. Bthreads do not directly know about each other.

### `on`

`on` takes an event type and a function of an event to a bid.

For example:

```clojure
(require '[next.jdbc.sql :as sql])
(require '[tech.thomascothran.pavlov.bthread :as b])

(defn create-account!
  [db-conn {:keys [account]}]
  (sql/insert! db-conn :account account)
  {:request #{{:event-type :account-created}}})

(def make-create-account-bthread
  [db-conn]
  (b/on :create-account create-account!))
```

When the `:create-account` event is selected, the create account bthread is notified, record is inserted, and the `:account-created` event is requested.

The function passed to `on` should not throw an error. If an error is thrown:

- it will be caught,
- an event of type `:tech.thomascothran.pavlov.bthread/unhandled-step-fn-error` will be requested
- that event will terminate the program (unless it is blocked)

### `after-all`

Use `after-all` when you want to coordinate several prerequisites and only continue once they have all happened. This is especially helpful when distinct systems (or bthreads) each publish their own completion events but downstream work must begin only after every prerequisite event has been selected—for example, waiting for both payment authorization and packaging to finish before marking an order ready to ship.

`after-all` takes a set of event types and a function `f`. The bthread waits on every event type in the set, in any order. Once each event type has been seen, `f` is invoked with a vector of the events in the order they arrived; the value returned by `f` becomes the next bid. After the bid from `f` is emitted, the bthread terminates and ignores further notifications.

```clojure
(require '[tech.thomascothran.pavlov.bthread :as b])

(def order-ready
  (b/after-all #{:payment/authorized :packing/completed}
               (fn [events]
                 (let [order-id (->> events (keep :order/id) first)]
                   {:request #{{:type :order/ready
                                :order/id order-id
                                :sources (mapv :type events)}}}))))

;; Your bprogram will call notify!, your application code will not call
;; notify! directly. But notify! can be used at the REPL to see what
;; the bthread does.

(b/notify! order-ready nil)
;; => {:wait-on #{:packing/completed :payment/authorized}}

(b/notify! order-ready {:type :payment/authorized :order/id 42})
;; => {:wait-on #{:packing/completed :payment/authorized}}

(b/notify! order-ready {:type :packing/completed :order/id 42})
;; => {:request #{{:type :order/ready
;;                 :order/id 42
;;                 :sources [:payment/authorized :packing/completed]}}}
```

In this example the `:order/ready` event is only requested after both upstream events have run, regardless of which one arrives first.

### Sequence Bthreads

`b/bids` can create a bthread out of a sequence of bids. It is only for finite, relatively short sequences.

For example:

```clojure
(b/bids
 [{:wait-on #{:good-morning}
   :block #{:good-evening}}
  {:wait-on #{:good-evening}
   :block #{:good-morning}}])
```

This will return a bid twice, then the bthread will be deregistered.

Note that `b/bids` fully realizes any sequence in memory!

There are several other ways to work with sequences. A map literal representing a bid is a bthread that will always return itself.

```clojure
{:request #{:fireworks}}  ;; Fireworks are always fun
```

If you want to set the fireworks off 10,000 times, you can use `repeat`:

```clojure
(b/repeat
  10000
  {:request #{:fireworks}})     ;; <- this event is requested
```

You can also create a bthread that notifies bthreads in round-robin fashion.

```clojure
(b/round-robin
 [{:wait-on #{:good-morning}
   :block #{:good-evening}}
  {:wait-on #{:good-evening}
   :block #{:good-morning}}])
```

### General Purpose Bthreads with `b/thread`

`b/thread` is a macro that makes creating bthreads both easy and expressive, and prevents mistakes that are easy to make.

```clojure
(require '[tech.thomascothran.pavlov.bthread :as b])

(b/thread [prev-state event] ;; must be a vector of 2, destructuring not supported
  :pavlov/init         ;; <- always required in the first position to initialize bthread
  [{:initialized true} ;; <- initialized bthread state
   {:wait-on #{:launch-rocket}}] ;; <- bid, wait until someone
                                 ;; wants to fire missiles

  :launch-rocket ;; when this event occurs, execute form
  (let [result (rocket-api/launch!)] ;; do something
    [prev-state                      ;; return previous state and bid
     {:request #{{:type :rocket-launched
                  :result result}}}])

  ;; if bthread notified of any other event, then return the previous
  ;; state and this bid.
  [prev-state {:wait-on #{:launch-rocket}}])
```

You will notice that the structure of `b/thread` is similar to using `defn` with `case`. The `[prev-state event]` form binds `prev-state` to the bthread's previous state. `event` is bound to the event about which the bthread is being notified.

The rest of the body of `b/thread` is similar to a `case` statement, switching on the type of an event (`:fire-missiles` in the example above).

Similar to `case`, a final form may be provided, which is a default if none of the events match. If no default value is provided, the bthread's state will not change, but it will not subscribe to any events -- meaning it is permanently parked.

Each form must return a tuple of the next state and a bid.

`b/thread` helps avoid some beginner traps with behavioral programming. For examples, see the [decision record on the b/thread macro](./doc/design/003_bthread-macro.md).

If you prefer not to use a macro, use the step functions.

#### Errors in `b/thread` execution

You should not throw an error inside of `b/thread`. If an error occurs, it will be caught, and a `:tech.thomascothran.pavlov.bthread/unhandled-step-fn-error` event will be emitted, terminating the program.

### Step Functions

The low-level, general purpose way to create a bthread is to use a step function.

A step function takes its previous state and an event, and returns its next state and a bid.

As an example:

```clojure
(require '[tech.thomascothran.pavlov.bthread :as b])

(defn only-thrice
  [{:keys [count done?] :as state} event]
  (cond
    (nil? event)
    [{:count 0} {:wait-on #{:test}}]

    done?
    [state nil]

    (< count 2)
    [{:count (inc count)} {:wait-on #{:test}}]

    :else
    [{:count (inc count) :done? true} nil]))

(def count-down-bthread (b/step only-thrice))

;; b/notify! will never be called in real application code,
;; but it is useful at the REPL to see what the bthread does.
[(b/notify! count-down-bthread nil)
 (b/notify! count-down-bthread {:type :test})
 (b/notify! count-down-bthread {:type :test})
 (b/notify! count-down-bthread {:type :test})
 (b/state count-down-bthread)]
;; => [{:wait-on #{:test}}
;;     {:wait-on #{:test}}
;;     {:wait-on #{:test}}
;;     nil
;;     {:count 3, :done? true}]
```

The bthread keeps track of its state, and the behavioral program keeps track of this (and all other) bthread bids.

The step function is called once on initialization with a `nil` event. Thereafter, it parks until the `:test` event is emitted. After the third `:test` event the bid returns `nil`, and subsequent notifications leave the bthread parked with the `:done?` flag set.

#### Errors in Step Functions

Step functions should not throw errors. If an error occurs, it will be caught, and a `:tech.thomascothran.pavlov.bthread/unhandled-step-fn-error` event will be emitted, terminating the program.

### Extensibility

Bthreads, bids, and behavioral programs are all protocols, allowing you to extend each as needed.

## Recipes

### Request a simple event

The simplest way to specify an event to request the name of the event:

```clojure
{:request #{:a}}
```

The bid protocol is extended to clojure maps. This results in a bthread that will always request `:a`.

Perhaps you just want to request `:a` once.


```clojure
(b/bids [{:request #{:a}}]) ;; => the event is the same as {:type :a}
```

This bthread requests an event of type `:a` once. Then the bthread terminates.

### Add more data to an event

Events can also be maps.

For example:

```clojure
{:type :submit
 :form {:first-name "Thomas"}
```

All bthreads that subscribe to `:submit` events now have access to the form data as well.

### Compound events

Events need not be a keyword, or even an atomic type.

For example, if you are playing tic tac toe, you may have `:x` select the center of the board:

```clojure
{:type [1 1 :x]}
```

### Block until

Combine `:wait` and `:block`:

```clojure
(b/bids [{:wait-on #{:b}
          :block #{:c}}])
```

Event `:c` is blocked until `:b` occurs. Then the bthread terminates

### Cancel `x` when `y` occurs

Combine `:wait-on` and `:request`:

```clojure
(def bthread-one
  (b/bids [{:wait-on #{:b}
            :request #{:a}}]))

(def bthread-two
  (b/bids [{:block #{:a}
            :wait-on #{:c}}]))
```

`bthread-two` blocks event `:a`.

If event `:c` occurs first, then `:a`'s request will succeed. (Assuming it is not blocked by other threads.)

However, if event `:b` occurs before event `:c`, then `:a` is cancelled.

### Terminate the bprogram

When `:c` occurs, close the program.

```clojure
(b/bids [{:wait-on #{:c}}
         {:terminate true ;; <- causes the program to stop.
          :type :finis}])
```

## BPrograms

To run an ephemeral bprogram, use one of the two main API functions in `tech.thomascothran.pavlov.bprogram.ephemeral`:

- `execute!`: returns a promise that is delivered when the bprogram terminates with the value of the terminal event. It allows you to call a bprogram like a function
- `make-program!`: returns the bprogram itself. This lets you send it new events, for example, with your subscribers.

An ephemeral bprogram is distinguished from a durable bprogram. Bprograms are implemented in terms of the BProgram protocol.

It has two arities, one that takes only a sequence of bthreads, and the other that takes bthreads and a map of options.

The most common items in the options map will be `:subscribers`. `:subscribers` is a map of the subscriber name to the subscriber function. Subscribers are invoked when the bprogram emits an event. These are invoked synchronously.

## Subscribers

Subscribers are functions that are called on every event. They are useful for logging and development tools.

Subscribers may be passed in when the bprogram is created:

```clojure
(require '[tech.thomascothran.pavlov.bprogram.ephemeral :as bpe])
(require '[clojure.pprint :refer [pprint]])

(def subscribers
  {:logger (fn [event bthread->bid]
             (pprint {:event event
                      :bthread->bid bthread->bid}))})

@(bpe/execute!
   [[:bthread-1 {:request #{:event-a}}]
    [:bthread-2 {:request #{:event-b}}]]
   {:subscribers subscribers
    :kill-after 50})
```

See the namespace `tech.thomascothran.pavlov.subscribers.tap` for an implementation of a subscriber.

## Understanding Program Execution

It may seem that behavioral programming makes programs both harder to reason about and harder to visualize as they execute. In fact, the opposite is the case.

### Pavlov Program Navigator (Portal Integration)

The Pavlov Program Navigator is a visual exploration tool that connects your behavioral programs (b-threads) in Clojure with Portal, allowing you to walk through possible execution paths-—including branching decisions and history—click by click.

For example, a tic-tac-toe behavioral program can be viewed as a graph that begins with 9 edges representing the 9 choices that player `x` might make. Click on one of those choices, and now you have 8 edges representing player `o`'s choices.

At each step of the program execution, you can see:

- What the available branches are (what possible events may be emitted)
- What the state of each bthread is
- The history of events that led to this program state
- The bids from all bthreads


### Visualizing program execution

Despite the ability to run concurrently, every step in the bprogram's execution is deterministic and auditable. Each bthread submits a bid, and the logic for selecting a bid is straightforward: the highest priority, unblocked bid is selected.

`pavlov`'s bprogram takes functions in the `subscribers` options map, which are invoked on each sync point.

The functions in `subscribers` are invoked with two arguments: the selected bid, and a map of each bthread to its bid.

A `tap` subscriber is implemented in `tech.thomascothran.pavlov.subscribers.tap`. This subscriber shows, on each sync:

- What event was selected
- What events are blocked
- What events were requested
- What events are being waited on
- A map of bthreads to bids
- A map of events to bthreads


Here is an example of how the tap publisher can be used with [portal](https://github.com/djblue/portal).

```clojure
(require '[portal.api :as portal])
(require '[tech.thomascothran.pavlov.subscribers.tap :as taps])
(require '[tech.thomascothran.pavlov.bprogram.ephemeral :as bpe])

;; Set up portal
(def p (portal/open))
(add-tap #'portal/submit)

;; Run the program
@(bpe/execute!
   [[:bthread-1 {:request #{:event-a}}]
    [:bthread-2 {:request #{:event-b}}]]
   {:subscribers {:tap taps/subscriber}
    :kill-after 50}) ;; <- add the tap
```

### Reasoning about bprograms

`pavlov` is intrinsically easier to reason about than a typical program for a few reasons:

- strict separation of logic from side effects. bthreads in pavlov are pure functions
- strong isolation of behavior. Each bthread encapsulates a single behavior and shares no state. Bthreads can be tested in isolation
- append only programming. This is enabled by the ability of a bthread to block another bthread.
- behavioral programming lends itself to model checking - without the need to write TLA+


## Further Reading

- [Behavioral Programming](https://cacm.acm.org/research/behavioral-programming/#R26), by David Harel, Assaf Marron, and Gera Weiss (2012)
- [The Behavioral Programming Web Page](https://www.wisdom.weizmann.ac.il/~bprogram/more.html)
- [Programming Coordinated Behavior in Java](https://www.wisdom.weizmann.ac.il/~/bprogram/pres/BPJ%20Introduction.pdf) by David Harel, Assaf Marron, and Gera Weiss.
- [Documentation and Examples for BPJ](https://wiki.weizmann.ac.il/bp/index.php/User_Guide)

## License

Copyright © 2025 Thomas Cothran

Distributed under the Eclipse Public License version 1.0.
