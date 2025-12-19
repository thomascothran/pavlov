# What is a bthread?

In behavioral programming, a *behavioral thread* (bthread) is the smallest unit of behavior you compose into a behavioral program. Every bthread receives a stream of events and returns *bids* that describe how it wants the program to evolve next.

Bthreads never talk to each other directly—coordination happens exclusively through the events they request, wait on, or block.

Bthreads can be composed with each other freely. Different behavioral programs can use the same bthreads in different combinations to achieve different overall behavior.

This document describes how to create an individual bthread and explore its behavior in isolation at the REPL or in a test -- without needing to start up a full behavioral program.

## Creating bthreads

Bthreads are stateful. Therefore, you should not `def` a bthread at the top level of a namespace. Instead, define a function that returns a new instance of the bthread each time it is called.

## Lifecycle and bids

A bthread is always invoked through `tech.thomascothran.pavlov.bthread/notify!`. You will never call `notify!` directly in your application code. Instead, you will create a behavioral program, and it will handle calling `notify!` on each bthread as events are dispatched. See, for example, `tech.thomascothran.pavlov.bprogram.ephemeral/execute!`.

However, `notify!` is very useful at the REPL and in tests.

The behavioral program will first call uses a `nil` event so the bthread can initialize its state and announce the events it cares about: events that are either requested or waited on. Afterwards the bthread is only reactivated when the behavioral program dispatches an event that matches one of the event types it waited on or requested.

Each time the bthread runs it returns a *bid*, a map that can contain any combination of:

- `:request` — a collection of events the bthread would like the program to select next
- `:wait-on` — event types that should wake this bthread up the next time they occur
- `:block` — event types that should be prevented from running while this bid is active

The behavioral program collects the bids from every active bthread, filters out any events that are currently blocked, and then selects the highest-priority unblocked event. Bthreads have priority amongst themselves when they are in an ordered collection:

```clojure
(def bthreads
  [[:bthread-a (make-bthread-a)]   ;; a has priority
   [:bthread-b (make-bthread-b)]])
```

Bthreads that supplied an *ordered* request collection (typically a vector or list) set an explicit priority for the events inside the bid—the earliest element wins. If bthreads are provided in an unordered collection (a set or map), their priority is non-deterministic:

```clojure
(def bthreads
  {:bthread-a (make-bthread-a)   ;; no priority, selected non-deterministically
   :bthread-b (make-bthread-b)})
```

The first selected bthread with an unblocked request will result in one of its requested events being selected. If the request is unordered (a set), one of the requested events is selected non-deterministically. If the request is ordered (a vector or list), the first unblocked event in the request is selected.

```clojure
(def bthread-a
  (b/bids [{:request #{{:type :a1} {:type :at}}}]))  ;; event selected non-deterministically

(def bthread-b
  (b/bids [{:request [{:type :b1} {:type :b2}]}])) ;; :b1 has priority over :b2
```

## The step function is the core

At the heart of Pavlov’s bthread story is the step function. This is not a feature of standard behavioral programming but a Pavlov-specific convention that enables a number of capabilities.

A step function is a pure function that receives the previous state of the bthread and the event selected by the bprogram's algorithm, and returns the new state plus the next bid (the `:request`, `:wait-on`, and `:blocked`) map.

`tech.thomascothran.pavlov.bthread/step` wraps such a function with the plumbing the behavioral program expects.

There are convenience functions to create bthreads which have some nice advantages. If one of those other bthread functions meets your needs, prefer it to using a step function directly.

```clojure
(require '[tech.thomascothran.pavlov.bthread :as b])

(def three-ticks
  (b/step (fn [state _event]
            (cond
              (nil? state)
              [0 {:wait-on #{:tick}}]

              (< state 2)
              [(inc state) {:wait-on #{:tick}}]

              (= state :finished)
              [:finished nil]

              :else
              [:finished {:request #{{:type :counter/done}}}]])))
```

Stepping the bthread at the REPL shows the complete lifecycle:

```clojure
[(b/notify! three-ticks nil)
 (b/notify! three-ticks {:type :tick})
 (b/notify! three-ticks {:type :tick})
 (b/notify! three-ticks {:type :tick})
 (b/notify! three-ticks {:type :tick})]
;; => [{:wait-on #{:tick}}
;;     {:wait-on #{:tick}}
;;     {:wait-on #{:tick}}
;;     {:request #{{:type :counter/done}}}
;;     nil]
```

On initialization the bthread announces that it cares about `:tick`. After three ticks it requests a terminal `:counter/done` event and then yields `nil`, which deregisters the bthread.

## Helper constructors

Writing step functions directly is flexible but verbose. Pavlov provides convenience constructors that build common bthread patterns on top of `step`. This not only reduces boilerplate but also makes your intent clearer.

### `b/bids` — finite scripted behavior

Use `b/bids` when you want to replay a finite sequence of bids. The bthread walks the sequence once and removes itself when the sequence is exhausted.

Items in the sequence may be:
- Bid maps (or any bthread)
- Functions of event to bid: `(fn [event] -> bid)`

Functions are detected with `fn?` and called with the current event, allowing bids to be computed dynamically.

```clojure
(def staged-requests
  (b/bids
   [{:request #{:prep/begin}}
    {:request #{:prep/finish}}
    {:request #{:ship}}]))

[(b/notify! staged-requests nil)
 (b/notify! staged-requests {:type :prep/begin})
 (b/notify! staged-requests {:type :prep/finish})
 (b/notify! staged-requests {:type :ship})]
;; => [{:request #{:prep/begin}}
;;     {:request #{:prep/finish}}
;;     {:request #{:ship}}
;;     nil]
```

You can also mix literal bids with functions that compute bids from event data:

```clojure
(def order-flow
  (b/bids
   [{:wait-on #{:order/placed}}
    (fn [event]
      {:request #{{:type :order/confirm
                   :order-id (:order-id event)}}})]))
```

### `b/on` — react to a specific event

`b/on` is ideal when you need a bthread that wakes up for exactly one event type and computes a new bid from the current event -- without keeping any state.

```clojure
(def review-on-receipt
  (b/on :invoice/received
        (fn [event]
          {:request #{{:type :invoice/reviewed
                       :invoice/id (:invoice/id event)}}})))

[(b/notify! review-on-receipt nil)
 (b/notify! review-on-receipt {:type :invoice/received :invoice/id 17})
 (b/notify! review-on-receipt {:type :invoice/reviewed :invoice/id 17})]
;; => [{:wait-on #{:invoice/received}}
;;     {:request #{{:type :invoice/reviewed, :invoice/id 17}},
;;      :wait-on #{:invoice/received}}
;;     {:wait-on #{:invoice/received}}]
```

The handler runs only when the subscribed event arrives—even though it requests `:invoice/reviewed`, that follow-up event will not retrigger the handler.

### `b/after-all` — wait for several prerequisites

`b/after-all` coordinates independent event sources. It waits until every event type in the provided set has occurred (in any order) before forwarding to the supplied function.

```clojure
(def ready-when-packed
  (b/after-all #{:payment/authorized :packing/completed}
               (fn [events]
                 (let [order-id (->> events (keep :order/id) first)]
                   {:request #{{:type :order/ready
                                :order/id order-id
                                :sources (mapv :type events)}}}))))

[(b/notify! ready-when-packed nil)
 (b/notify! ready-when-packed {:type :packing/completed :order/id 42})
 (b/notify! ready-when-packed {:type :payment/authorized :order/id 42})
 (b/notify! ready-when-packed {:type :extra :order/id 42})]
;; => [{:wait-on #{:packing/completed :payment/authorized}}
;;     {:wait-on #{:packing/completed :payment/authorized}}
;;     {:request #{{:type :order/ready,
;;                  :order/id 42,
;;                  :sources [:packing/completed :payment/authorized]}}}
;;     nil]
```

Once all prerequisites are satisfied the bthread emits its completion request and then terminates.

### `b/thread` — declarative branching

The `b/thread` macro lets you describe a bthread as a set of event-specific clauses, similar to writing a `case`. It is great when the bthread maintains meaningful state across several event types.

```clojure
(def door-alarm
  (b/thread [state event]
    :pavlov/init
    [{:door :closed}
     {:wait-on #{:door/opened}}]

    :door/opened
    [{:door :open}
     {:wait-on #{:door/closed}
      :block #{:door/opened}
      :request #{{:type :alarm/check}}}]

    :door/closed
    [{:door :closed}
     {:wait-on #{:door/opened}
      :request #{{:type :alarm/reset}}}]

    ;; remember this! This is easy to forget. This will be called
    ;; when an event is received that doesn't match any other clause.
    [state {:wait-on #{:door/opened :door/closed}}]))

[(b/notify! door-alarm nil)
 (b/notify! door-alarm {:type :door/opened})
 (b/notify! door-alarm {:type :door/closed})
 (b/notify! door-alarm {:type :door/locked})]
;; => [{:wait-on #{:door/opened}}
;;     {:wait-on #{:door/closed},
;;      :block #{:door/opened},
;;      :request #{{:type :alarm/check}}}
;;     {:wait-on #{:door/opened},
;;      :request #{{:type :alarm/reset}}}
;;     {:wait-on #{:door/opened :door/closed}}]
```

Here the bthread blocks a second `:door/opened` event while the door is already open, requests downstream checks, and keeps listening for state changes until it terminates or the program stops.

---

Bthreads give you a lightweight way to isolate behavior into independent units. Understanding how they consume events, produce bids, and leverage helper constructors makes it straightforward to model complex coordination without entangling logic or state between components.
