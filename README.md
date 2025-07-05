# Pavlov: Behavioral Programming for Clojure

*Status: pre-release.*

[![Clojars Project](https://img.shields.io/clojars/v/tech.thomascothran/pavlov.svg)](https://clojars.org/tech.thomascothran/pavlov)

Pavlov is an opinionated behavioral programming library for Clojure(Script).

Behavioral programming (BP) is an event-driven programming paradigm that strongly decomplects application behaviors.

Pavlov can be used for strongly Pavlov also supports using a behavioral program as a synchronous function call.

![bprogram diagram](./doc/assets/bprogram.png)

## Bthreads

In BP, a unit of application behavior is a bthread. Bthreads can be run in parallel. Bthreads park until an event they are interested in occurs.

Bthreads work by producing bids in a certain kind of pub-sub system -- a bprogram. A bid can:

1. Request events
2. Wait on events
3. Block events

Events may come from an external process. This can be anything: not only a bid from a bthread, but a user action in a UI, an event from a Kafka queue, an HTTP request, etc.

When an event occurs, all bthreads that have either requested that event or are waiting on that event submit their next bid.

Bthreads should be pure functions. Use subscribers for side effects.

## Bprograms

The bprogram will select the next event based on the bids. Any event that is blocked by any bthread will never be selected. Importantly, this means bthreads block events requested by other bthreads.

Bthreads are assigned a priority. The bprogram selects the bthread with a) the highest priority and b) at least one requested event that is not blocked.

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
            [tech.thomascothran.pavlov.bprogram :as bp]))

(def water-app
  (let [add-hot  (b/bids (b/reprise 3 {:request #{:add-hot-water}}))
        add-cold (b/bids (b/reprise 3 {:request #{:add-cold-water}}))
        alt-temp (b/bids
                    (b/interlace
                       (b/reprise {:wait-on #{:add-cold-water}
                                   :block #{:add-hot-water}})
                       (b/reprise {:wait-on #{:add-hot-water}
                                   :block #{:add-cold-water}})))]
    (bp/make-program [add-hot add-cold alt-temp]))
```

## Creating bthreads

Bthreads are sequential and stateful. They can run in parallel and be parked when they are waiting on events.

The bid a bthread produces can request events, wait on events, or block events in other bthreads. Bthreads do not directly know about each other.

### Step Functions

The default way to create a bthread is to use a step function.

A step function takes its previous state and an event, and returns its next state and a bid.

As an example:

```clojure
(require '[tech.thomascothran.pavlov.bthread :as b])

;; Pure function
(defn only-thrice
  [prev-state event]
  (cond (not event) ;; initialization
        [0 {:wait-on #{:test}}]

        (< prev-state 3)
        [(inc prev-state) {:wait-on #{:test}}]))

(def count-down-bthread
  (b/step ::count-down-bthread only-thrice))
```

The bthread will keep track of the state, and the behavioral program keeps track of this (and all other) bthread bids.

The `::count-down-bthread` is called once on initialization. Thereafter, it parks until the `:test` event is emitted. On the third time, the bthread returns nil, at which point it will not be called anymore.

bthreads *must have unique names*. If more than one bthread has the same name, one bthread will be ignored.

### Sequence Functions

`b/bids` can create a bthread out of a sequence of bids. It is only for finite, relatively short sequences.

For example:

```clojure
(b/bids
 [{:wait-on #{:good-morning}
   :block #{:good-evening}}
  {:wait-on #{:good-evening}
   :block #{:good-morning}}])]
```

This will return a bid twice, then the bthread will be deregistered.

Note that `b/bids` fully realizes any sequence in memory!

There are several other ways to work with sequences. A map literal representing a bid is a bthread that will always return itself.

```clojure
{:request #{:fireworks}}  ;; Fireworks are always fun
```

If you want to set the fireworks off 10,000 times, you can use `reprise`:

```clojure
(b/reprise
  10000
  {:request #{:fireworks}})     ;; <- this event is requested
```

If you want something like `interleave` for bthreads, you can use `interlace`.

For example:

```clojure
(b/interlace
 [{:wait-on #{:good-morning}
   :block #{:good-evening}}
  {:wait-on #{:good-evening}
   :block #{:good-morning}}])]
```

`interlace` is a little different than `interleave`.

With interleave:

```clojure
(interleave [:a :b] [1])
;; => [:a 1]
```

However, with interlace:

```clojure
(interlace
  [(b/bids [{:request #{:a :b}}
            {:request #{1}}]))
```

Interlace will return *three* bids, for events `:a`, `1`, and `:b`.

### Extensibility

Bthreads are protocols. You can extend the protocol as needed.

## Recipes

### Request a simple event

The simplest way to specify an event to request the name of the event:

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
            :wait-on #{:c}}])

```

`bthread-two` blocks event `:a`.

If event `:c` occurs first, then `:a`'s request will succeed. (Assuming it is not blocked by other threads.)

However, if event `:b` occurs before event `:c`, then `:a` is cancelled.

### Terminate the bprogram

When `:c` occurs, close the program.

```clojure
(b/bids [{:wait-on #{:c}}
         {:terminate true
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

`pavlov` strictly separates side effects from pure computation. `bthreads` should be pure functions.

Subscribers may be passed in when the bprogram is created:

```clojure
(require '[tech.thomascothran.pavlov.bprogram.ephemeral :as bp])
(require '[clojure.pprint :refer [pretty-print]])
(bp/execute! [bthread-1 bthread-2]
             {:logger (fn [event bthread->bid]
                        (pretty-print {:event event
                                       :bthread->bid bthread->bid})})
```

## Understanding Program Execution

It may seem that behavioral programming makes programs both harder to reason about and harder to visualize as they execute. In fact, the opposite is the case.

### Visualizing program execution

Despite the ability to run concurrently, every step in the bprogram's execution is deterministic and auditable. Each bthread submits a bid, and the logic for selecting a bid is straightforward: the highest priority, unblocked bid is selected.

`pavlov`'s bprogram takes functions in the `subscribers` options map, which are invoked on each sync point.

The functions in `subscribers` re invoked with two arguments: the selected bid, and a map of each bthread to its bid.

A `tap` subscriber is implemented in `tech.thomascothran.pavlov.subscribers.tap`. This subscriber shows, on each sync:

- What event was selected
- What events are blocked
- What events were requested
- What events are being waited on
- A map of bthreads to bids
- A map of events to bthreads

### Reasoning about bprograms

`pavlov` is intrinsically easier to reason about than a typical program for a few reasons:

- strict separation of logic from side effects. bthreads in pavlov are pure functions
- strong isolation of behavior. Each bthread encapsulates a single behavior and shares no state. Bthreads can be tested in isolation
- append only programming. This is enabled by the ability of a bthread to block another bthread.
- behavioral programming lends itself to model checking - without the need to write TLA+

## Design Goals

1. *Zero dependencies*.
2. *Swappable implementations*. Bthreads and bprograms are open for extension and modification.
3. *BYO parallelization*. Bthreads can run in parallel and you should choose how. Bring your own thread pool, or use core.async.

## Roadmap

| Description                        | Started            | Complete           |
|------------------------------------|--------------------|--------------------|
| Test canonical tic tac toe example | :heavy_check_mark: | :heavy_check_mark: |
| Document common idiom s            | :heavy_check_mark: | :heavy_check_mark: |
| Clojure(Script) support            | :heavy_check_mark: | :heavy_check_mark: |
| Bring your own parallelization     |                    |                    |
| Squint support                     | :heavy_check_mark: |                    |
| Example web app                    |                    |                    |
| Generate Live Sequence Charts      |                    |                    |
| Automated model checking           |                    |                    |
| Durable Execution                  | :heavy_check_mark: |                    |

## Further Reading

- [Behavioral Programming](https://cacm.acm.org/research/behavioral-programming/#R26), by David Harel, Assaf Marron, and Gera Weiss (2012)
- [The Behavioral Programming Web Page](https://www.wisdom.weizmann.ac.il/~bprogram/more.html)
- [Programming Coordinated Behavior in Java](https://www.wisdom.weizmann.ac.il/~/bprogram/pres/BPJ%20Introduction.pdf) by David Harel, Assaf Marron, and Gera Weiss.
- [Documentation and Examples for BPJ](https://wiki.weizmann.ac.il/bp/index.php/User_Guide)

## License

Copyright © 2024 Thomas Cothran

Distributed under the Eclipse Public License version 1.0.
