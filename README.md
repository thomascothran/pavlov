# Pavlov: Behavioral Programming for Clojure

*Status: pre-release. Don't use.*

Pavlov is a behavioral programming library for Clojure(Script).

Behavioral programming is an event-driven programming paradigm that emphasizes independently defining behaviors and composing them together.

To understand behavioral programming, the following resources are useful:

- [Behavioral Programming](https://cacm.acm.org/research/behavioral-programming/#R26), by David Harel, Assaf Marron, and Gera Weiss (2012)
- [The Behavioral Programming Web Page](https://www.wisdom.weizmann.ac.il/~bprogram/more.html)
- [Programming Coordinated Behavior in Java](https://www.wisdom.weizmann.ac.il/~/bprogram/pres/BPJ%20Introduction.pdf) by David Harel, Assaf Marron, and Gera Weiss.
- [Documentation and Examples for BPJ](https://wiki.weizmann.ac.il/bp/index.php/User_Guide)

## Example

```clojure
(ns water-controls.app
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram :as bp]))

(def water-app
  (let [add-hot  (b/seq (repeat 3 {:request #{:add-hot-water}}))
        add-cold (b/seq (repeat 3 {:request #{:add-cold-water}}))
        alt-temp (b/seq 
                    (interleave
                       (repeat {:wait-on #{:add-cold-water}
                                :block #{:add-hot-water}})
                       (repeat {:wait-on #{:add-hot-water}
                                :block #{:add-cold-water}})))]
    (bp/make-program [add-hot add-cold alt-temp]))
```

## Bthreads

A bthread is a unit of behavior that produces a `bid` each time it is called. Bthreads are sequential and stateful. They can run in parallel and be parked when they are waiting on events.

The bid a bthread produces can request events, wait on events, or block events in other bthreads. Bthreads do not directly know about each other.

There are two main functions to create bthreads.

- `b/seq`: turn a sequence of bids into a bthread
- `b/reduce`: turn a reducing function and a starting value into a bthread

##

## Recipes

### Request a simple event

The simplest way to specify an event is as a keyword:

```clojure
(b/seq [{:request {:a}}])
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

### Cancel when

Combine `:wait-on` and `:request`:

```clojure
(def bthread-one
  (b/seq [{:wait-on #{:b}
           :request #{:a}}]))

(def bthread-two
  (b/seq [{:block #{:a}
           :wait-on #{:c}}])

```

`bthread-two` blocks event `:a`.

If event `:c` occurs first, then `:a`'s request will succeed. (Assuming it is not blocked by other threads.)

However, if event `:b` occurs before event `:c`, then `:a` is cancelled.

### Terminate the bprogram

When `:c` occurs, close the program.

```clojure
(b/seq [{:wait-on #{:c}}
        {:terminate true
         :type :finis}])
```

## Roadmap

1. Implement canonical examples in the test suite
2. Abstract event sources and sinks
3. Firm up APIs
4. ClojureScript support
5. Opt-in parallelization
6. Documentation

## License

Copyright © 2024 Thomas Cothran

Distributed under the Eclipse Public License version 1.0.
