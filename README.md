# Pavlov: Behavioral Programming for Clojure

*Status: pre-release.*

[![Clojars Project](https://img.shields.io/clojars/v/tech.thomascothran/pavlov.svg)](https://clojars.org/tech.thomascothran/pavlov)

Pavlov is a behavioral programming library for Clojure(Script).

Behavioral programming (BP) is an event-driven programming paradigm that decomplects application behaviors.

![bprogram diagram](./doc/assets/bprogram.png)

## Bthreads

In BP, a unit of application behavior is a bthread. Bthreads can be run in parallel. Bthreads park until an event they are interested in occurs.

Bthreads work by producing bids in a certain kind of pub-sub system -- a bprogram. A bid can:

1. Request events
2. Wait on events
3. Block events

Events may come from an external process. This can be anything: not only a bid from a bthread, but a user action in a UI, an event on a Kafka stream, an HTTP request, etc.

When an event occurs, all bthreads that have either requested that event or are waiting on that event submit their next bid.

## Bprograms

The bprogram will select the next event based on the bids. Any event that is blocked by any bthread will never be selected. Importantly, this means bthreads block events requested by other bthreads.

Bthreads are assigned a priority. The bprogram selects the bthread with a) the highest priority and b) at least one requested event that is not blocked.

If all threads have the same priority, then the bid selection is random.

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
  (let [add-hot  (b/bids (repeat 3 {:request #{:add-hot-water}}))
        add-cold (b/bids (repeat 3 {:request #{:add-cold-water}}))
        alt-temp (b/bids 
                    (interleave
                       (repeat {:wait-on #{:add-cold-water}
                                :block #{:add-hot-water}})
                       (repeat {:wait-on #{:add-hot-water}
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

(defn only-thrice
  [prev-state _event]
  (cond (not prev-state)
        [1 {:wait-on #{:test}}]
        (< prev-state 2)
        [(inc prev-state) {:wait-on #{:test}}]))

(def count-down-bthread
  (bthread/step ::count-down-bthread count-down-step-fn))
```

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

Note that `b/bids` fully realizes any sequence in memory.

There are several other ways to work with sequences. A map literal representing a bid is a bthread that will always return itself.

```clojure
{:wait-on #{:the-thumbs-up} ;; <- when this event occurs
 :request #{:fireworks}}    ;; <- this event is requested
```

If you want to set the fireworks off 10,000 times, you can use `reprise`:

```clojure
(b/reprise
  {:wait-on #{:the-thumbs-up} ;; <- when this event occurs
   :request #{:fireworks}})     ;; <- this event is requested
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

Which is the same as:

```clojure
(b/interlace
 [(b/reprise {:wait-on #{:good-morning}
              :block #{:good-evening}}
  (b/reprise {:wait-on #{:good-evening}
              :block #{:good-morning}})])]
```

However, interlace is a little different than `interleave`.

With interleave:

```clojure
(interleave [:a :b] [1])
;; => [:a 1]
```

However, with interlace:

```clojure
(interlace
  [(b/bids [{:request #{:a :b}}
           {:request #{1}]))
;; interlace will return *three* bids, for
;; events `:a`, `:b`, and `1`
```

## Recipes

### Request a simple event

The simplest way to specify an event is as a keyword:

```clojure
(b/bids [{:request #{:a}}])
```

This bthread requests an event of type `:a` then halts

### Add more data to an event

Events can also be maps.

For example:

```clojure
{:type :submit
 :form {:first-name "Thomas"}
```

### Compound events

Events need not be an atomic type.

For example, if you are playing tic tac toe, you may have `:x` select the center of the board:

```clojure
{:type [1 1 :x]}
```

### Block until

Combine `:wait` and `:block`:

```clojure
{:wait-on #{:b}
 :block #{:c}
```

Event `:c` is blocked until `:b` occurs.

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

## Design Goals

1. *Zero dependencies*.
2. *Swappable implementations*. Protocols are used so that bthreads and bprograms are open for extension and modification.
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
