# Pavlov: Behavioral Programming for Clojure

Pavlov is an opinionated [behavioral programming](https://cacm.acm.org/research/behavioral-programming/#R26) library for Clojure(Script). Behavioral programming was invented by David Harel, who also invented statecharts. It has a solid theoretical foundation and radically simple in principle.

Pavlov differs from existing behavioral programming libraries in the following ways:

- Pavlov takes a functional, data-first approach, rather than the imperative style used by [BPjs](https://bpjs.readthedocs.io/en/latest/BPjsTutorial/hello-world.html#hello-block-world)
- Pavlov encourages [scenario-based programming](https://link.springer.com/book/10.1007/978-3-642-19029-2) and [statecharts](https://www.sciencedirect.com/science/article/pii/0167642387900359) as the go-to defaults for bthreads

## Design Goals

1. *Zero core dependencies*. Pavlov's core has 0 dependencies. Pavlov devtools has dependencies, but is used only during development and testing
2. *Cross Platform* - JVM, CLJS, Squint, and Babashka Support
3. *First class model checking*. Pavlov's programs can be used by its model checker - for free. No translating between TLA+, Spin, etc.
4. *Interactive program inspection*. Support `nav` so that tools like `portal` to inspect execution branches of a behavioral program.

## Modules

| Module | Status | Version |
|--------|--------|---------|
| core   | beta   | [![Clojars Project](https://img.shields.io/clojars/v/tech.thomascothran/pavlov.svg)](https://clojars.org/tech.thomascothran/pavlov) |
| devtools | beta | [![Clojars Project](https://img.shields.io/clojars/v/tech.thomascothran/pavlov-devtools.svg)](https://clojars.org/tech.thomascothran/pavlov-devtools) |
| skills | pre-alpha | [![Clojars Project](https://img.shields.io/clojars/v/tech.thomascothran/pavlov-skills.svg)](https://clojars.org/tech.thomascothran/pavlov-skills) |
| web | pre-alpha | [![Clojars Project](https://img.shields.io/clojars/v/tech.thomascothran/pavlov-web.svg)](https://clojars.org/tech.thomascothran/pavlov-web) |


## Getting Started
Pavlov encourages you to program in scenarios, and pavlov composes them for you. Let's take a simple, canonical example using an industrial process. You need three parts hot water and three parts cold water.

First, we need some bthreads. Behavioral programming uses a basic unit called a bthread (for "behavioral thread"). Bthreads encapsulate behaviors.  They park until events to which they are subscribed occur.

Bthreads are composed in a behavioral program. Here is a simple example:

```clojure
(ns water-controls.app
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]))

;; bthreads are stateful, so always use a constructor
(defn hot-water
  []
  (b/scenario [{:request #{:hot-water}}
               {:request #{:hot-water}}
               {:request #{:hot-water}}]))

(defn cold-water
  []
  (b/scenario [{:request #{:cold-water}}
               {:request #{:cold-water}}
               {:request #{:cold-water}}]))

@(bpe/execute! [[:hot-water (hot-water)]
               [:cold-water (cold-water)]]
              {:subscribers {:logger (fn [event _] (println event))}})

;; :hot-water
;; :hot-water
;; :hot-water
;; :cold-water
;; :cold-water
;; :cold-water
```

Simple enough. But what if you get a new business rule: hot and cold water must alternate. Filling a tank with all hot water will damage it. And the temperature should be as consistent as possible.

Usually, you need to add modify the existing code to add this new requirement. But with behavioral programming, you can use an *append only* programming style:

```clojure
(defn interleave-hot-and-cold
  []
  (b/scenario [{:wait-on #{:cold-water}
                :block #{:hot-water}} ;; <- block :hot-water until :cold-water
               (fn [_]
                 {:bid {:wait-on #{:hot-water}
                        :block #{:cold-water}}
                  :next-step :first})])) ;; <- restart at the top after hot water


@(bpe/execute! [[:hot-water (hot-water)]
               [:cold-water (cold-water)]
               [:interleave-hot-and-cold    ;; only change to
                (interleave-hot-and-cold)]] ;; existing code
              {:subscribers {:logger (fn [event _] (println event))}})

;; :cold-water
;; :hot-water
;; :cold-water
;; :hot-water
;; :cold-water
;; :hot-water
```

What's this `:request`, `:wait-on`, and `:block` stuff all about?

## Bthreads
Bthreads are assembled into a pub-sub system—a bprogram. Each bthread can:

1. Request events
2. Wait on events
3. Block events

These three things form the basic semantics of a behavioral program. Bthreads communicate what they want to do (or not do) with the `:request`, `:wait-on`, and `:block` keys in a map.

When an event occurs, all bthreads that have either requested that event or are waiting on that event submit their next bid. All other bthreads remained parked. (You can have many parked bthreads - they are cheap.)

For a deeper introduction to the lifecycle of bthreads and how bids work, see [What is a bthread?](./doc/what-is-a-bthread.md). To explore groups of bthreads interactively, see [Navigating Behavioral Programs](./doc/navigating-bprograms.md).

Bthreads are composed together into behavioral programs. These can be long-running in event-driven systems. Or they can be invoked as a synchronous function (as we did above with `bpe/execute!`.

### Scenario
The vast majority of the time, you will only need the `scenario` bthread constructor. A scenario is just a linear sequence.

We already saw this example:

```clojure
(defn hot-water
  []
  (b/scenario [{:request #{:hot-water}}
               {:request #{:hot-water}}
               {:request #{:hot-water}}]))
```

We call the map with the `:request`, `:wait-on`, or `:block` keys a bid.

But `scenario` can also take a function:

```clojure
(defn hot-water
  []
  (b/scenario [(fn [{_ :event, state :state}]
                 {:bid {:request #{:hot-water}}
                  :state (update state :ounces inc)})
               (fn [{_ :event, state :state}]
                 {:bid {:request #{:hot-water}}
                  :state (update state :ounces inc)})
               (fn [{_ :event, state :state}]
                 {:bid {:request #{:hot-water}}
                  :state (update state :ounces inc)})]
              {:initial-state {:ounces 0}}))
```

Because events can be maps, they can carry more information, such as the temperature of the water. The function also receives internal state, and can update that state by setting a `:state` value in its result map alongside `:bid`.

Because `scenario` is linear, it reads a lot like a test scenario. This is intentional!

## Bprograms
Bthreads are assembled into bprograms. The main purpose of a behavioral program is to select the next event, and notify all bthreads subscribed to that event type. Bthreads only subscribe to events if they request them or are waiting on them.

To run an ephemeral bprogram, use one of the two main API functions in `tech.thomascothran.pavlov.bprogram.ephemeral`:

- `execute!`: returns a promise that is delivered when the bprogram terminates with the value of the terminal event. It allows you to call a bprogram like a function
- `make-program!`: returns the bprogram itself. This lets you send it new events from the outside (i.e,. not from bthreads).

Bprograms have a simple algorithm for selecting the next event.

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

## Bthreads can spawn other bthreads

Bthreads are stateful. They can run in parallel and be parked when they are waiting on events.

The bid a bthread produces can request events, wait on events, or block events in other bthreads. Bthreads do not directly know about each other.

Bids may also spawn child bthreads via `:bthreads`. Spawned bthreads are keyed by name and initialized with `nil`. If spawned during init, they can observe the first event; if spawned in response to an event, they only observe subsequent events.

```clojure

(b/scenario [{:request #{:a}
              :bthreads {:child (b/scenario [{:request #{:b}}])}}])
```

## Bthread constructors
`scenario` is the default way to create bthreads, and will be likely be the best choice 90% of the bthreads you need. However, in some cases, a different constructor can be useful

### Literal map
This is a bthread that always requests `:fireworks`

```clojure
{:request #{:fireworks}}
```

### `repeat`
If you want to set the fireworks off 10,000 times, you can use `repeat`:

```clojure
(b/repeat
  10000
  {:request #{:fireworks}})     ;; <- this event is requested 10000 times
```

### `after-all`
Use `after-all` when you want to wait on a set of prior events, regardless of the order they occur in.

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

### Extensibility

Bthreads, bids, and behavioral programs are all protocols, allowing you to extend each as needed.

## Recipes

### Request an event once

The simplest way to specify an event to request the name of the event:

```clojure
{:request #{:a}}
```

The bid protocol is extended to clojure maps. This results in a bthread that will always request `:a`.

Perhaps you just want to request `:a` once.


```clojure
(b/scenario [{:request #{{:type :a}}}])
```

This bthread requests an event of type `:a` once. Then the bthread terminates.

### Add more data to an event

Events can also be maps.

For example:

```clojure
{:type :submit
 :form {:first-name "Thomas"}}
```

All bthreads that subscribe to `:submit` events now have access to the form data as well.

### Block until

Combine `:wait-on` and `:block`:

```clojure
(b/scenario [{:wait-on #{:b}
              :block #{:c}}])
```

Event `:c` is blocked until `:b` occurs. Then the bthread terminates

### Cancel `x` when `y` occurs

Combine `:wait-on` and `:request`:

```clojure
(defn bthread-one
  []
  (b/scenario [{:wait-on #{:b}
                :request #{:a}}]))

(defn bthread-two
  []
  (b/scenario [{:block #{:a}
                :wait-on #{:c}}]))
```

`bthread-two` blocks event `:a`.

If event `:c` occurs first, then `:a`'s request will succeed. (Assuming it is not blocked by other threads.)

However, if event `:b` occurs before event `:c`, then `:a` is cancelled.

### Terminate the bprogram

When `:c` occurs, close the program.

```clojure
(b/scenario [{:wait-on #{:c}}
             {:request #{{:type :finis
                          :terminal true}}}])
```

## Subscribers

Subscribers are functions that are called on every event. They are useful for logging and development tools. They may also be used for IO when you want to keep the bprogram itself pure. (However, depending on the use case, IO in bthreads can be fine.)

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

The pavlov devtools module is provided to help you visualize and understand program execution.

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

- strong isolation of behavior. Each bthread encapsulates a single behavior and shares no state. Bthreads can be tested in isolation
- append only programming. This is enabled by the ability of a bthread to block another bthread.
- behavioral programming lends itself to model checking - without the need to write TLA+

## Experimental Squint support

Pavlov core and the browser-facing parts of Pavlov Web can be compiled with
[Squint](https://github.com/squint-cljs/squint). The implementation and tests
are shared with full ClojureScript; Squint-specific files only configure test
and ESM entrypoints.

```bash
cd modules/pavlov
npm ci && npm run test:squint

cd ../pavlov-web
npm ci && npm run test:squint

cd ../../examples/web
npm ci && npm run build:squint
```

The example server exposes the Squint bundle at `/browser-only-squint` and
`/game-of-life-squint`; the original Shadow CLJS pages remain at
`/browser-only` and `/game-of-life`. Squint consumes Pavlov source roots rather
than Clojars JARs. A consuming monorepo can configure paths directly:

```clojure
{:paths ["src"
         "../pavlov/modules/pavlov/src"
         "../pavlov/modules/pavlov-web/src"]}
```

Support remains experimental and currently inherits Pavlov core's primitive
string/keyword bthread-name and event-type profile. Collection-valued names or
event types are not yet guaranteed under Squint.


## Further Reading

- [Behavioral Programming](https://cacm.acm.org/research/behavioral-programming/#R26), by David Harel, Assaf Marron, and Gera Weiss (2012)
- [The Behavioral Programming Web Page](https://www.wisdom.weizmann.ac.il/~bprogram/more.html)
- [Programming Coordinated Behavior in Java](https://www.wisdom.weizmann.ac.il/~/bprogram/pres/BPJ%20Introduction.pdf) by David Harel, Assaf Marron, and Gera Weiss.
- [Documentation and Examples for BPJ](https://wiki.weizmann.ac.il/bp/index.php/User_Guide)

## License

Copyright © 2025 Thomas Cothran

Distributed under the Eclipse Public License version 1.0.
