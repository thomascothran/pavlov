# What is a bthread?

In behavioral programming, a *behavioral thread* (bthread) is the smallest unit of behavior you compose into a behavioral program. Every bthread receives a stream of events and returns *bids* that describe how it wants the program to evolve next.

Bthreads never talk to each other directly—coordination happens exclusively through the events they request, wait on, or block.

Bthreads can be composed with each other freely. Different behavioral programs can use the same bthreads in different combinations to achieve different overall behavior.

This document describes how to create an individual bthread and explore its behavior in isolation at the REPL or in a test—without needing to start up a full behavioral program.

## Creating bthreads

Bthreads are stateful. Therefore, you should not `def` a bthread at the top level of a namespace. Instead, define a function that returns a new instance of the bthread each time it is called.

## Lifecycle and bids

A bthread is always invoked through `tech.thomascothran.pavlov.bthread/notify!`. You will never call `notify!` directly in your application code. Instead, you will create a behavioral program, and it will handle calling `notify!` on each bthread as events are dispatched. See, for example, `tech.thomascothran.pavlov.bprogram.ephemeral/execute!`.

However, `notify!` is very useful at the REPL and in tests.

The behavioral program first notifies a bthread with a `nil` event so it can initialize its state and announce the events it cares about: events that are either requested or waited on. Afterwards the bthread is only reactivated when the behavioral program dispatches an event it requested, or an event whose type it waited on.

Each time the bthread runs it returns a *bid*, a map that can contain any combination of:

- `:request` — a collection of events the bthread would like the program to select next
- `:wait-on` — event types that should wake this bthread up the next time they occur
- `:block` — event types that should be prevented from running while this bid is active
- `:bthreads` — child bthreads to spawn, keyed by name

### How events are matched

Requests are concrete events. Waits and blocks are event *types*.

- A request is satisfied only by an event equal (`=`) to the requested event. If one bthread requests `{:type :order-food :entree :steak}` and another requests `{:type :order-food :entree :vegan-salad}`, selecting the steak order resumes only the first bthread.
- A `:wait-on` or `:block` entry stands for its whole event type, as returned by `tech.thomascothran.pavlov.event/type`. `{:wait-on #{:order-food}}` wakes on every order, and so does `{:wait-on #{{:type :order-food :entree :steak}}}`. Likewise `{:block #{{:type :order-food :entree :steak}}}` blocks every `:order-food` event, not just steak orders.
- `:x` and `{:type :x}` are different events. They have the same type, so they match the same waits and blocks, but a request for one is not satisfied by the other.

This works for any implementation of the `tech.thomascothran.pavlov.event.proto/Event` protocol. On the JVM and in ClojureScript, the bprogram looks requested events up in a hash map, so:

- Requested events must be hashable. For example, a requested event holding an infinite lazy sequence throws when the bprogram indexes it.
- `=` and `hash` must agree. If two events are `=` but hash differently, whether one satisfies a request for the other can depend on how many events are currently requested.
- An event without its own equality, such as a `reify` or a `deftype` that does not implement `equals`/`IEquiv`, uses identity. It satisfies only a request for that same instance.

On Squint, `=` compares objects without their own equality by their fields, not by identity. Maps, vectors, keywords, records, and types that define their own equality behave the same on every platform; only objects without their own equality differ:

```clojure
(require '[tech.thomascothran.pavlov.event.proto :as event-proto])

(deftype Order [entree]          ; no equals / IEquiv
  event-proto/Event
  (type [_] :order-food)
  (terminal? [_] false))

(= (->Order :steak) (->Order :steak))
;; JVM, CLJS: false (different instances)
;; Squint:    true  (same fields)

(defn order [entree]
  (reify event-proto/Event
    (type [_] :order-food)
    (terminal? [_] false)))

(= (order :steak) (order :vegan-salad))
;; JVM, CLJS: false (different instances)
;; Squint:    true  (a reify has no fields to compare)
```

So on Squint, if two bthreads request events made by `order`, selecting either one resumes both. When targeting Squint, use maps or records for events, or give custom event types their own equality with `IEquiv`.

If a bthread requests an event but should also react when some other event of the same type is selected, it must also wait on that type:

```clojure
{:request #{{:type :order/placed :order-id 42}}
 :wait-on #{:order/placed}}
```

Spawned bthreads are initialized by being notified with a `nil` event. If they are spawned during startup they can observe the first event; if they are spawned in response to an event they will only observe subsequent events.

```clojure
(require '[tech.thomascothran.pavlov.bthread :as b])

(defn make-parent
  []
  (b/scenario
   [{:request #{:start}
     :bthreads {:child (b/scenario [{:wait-on #{:start}}
                                    {:request #{{:type :done
                                                 :terminal true}}}])}}]))
```

The behavioral program collects the bids from every active bthread, filters out any events that are currently blocked, and then selects the highest-priority unblocked event.

Bthreads have priority amongst themselves when they are provided in an ordered collection of name/bthread pairs:

```clojure
(def bthreads
  [[:bthread-a (make-bthread-a)]   ;; a has priority
   [:bthread-b (make-bthread-b)]])
```

If bthreads are provided in a map, they have equal priority and one is selected non-deterministically:

```clojure
(def bthreads
  {:bthread-a (make-bthread-a)   ;; no priority, selected non-deterministically
   :bthread-b (make-bthread-b)})
```

Requests have a priority order too. Once the highest-priority bthread with an unblocked request is chosen, one of its requested events is selected. If the request is unordered (a set), one of the requested events is selected non-deterministically. If the request is ordered (a vector or list), the first unblocked event in the request is selected.

```clojure
(defn make-bthread-a
  []
  (b/scenario [{:request #{{:type :a1} {:type :a2}}}]))  ;; selected non-deterministically

(defn make-bthread-b
  []
  (b/scenario [{:request [{:type :b1} {:type :b2}]}])) ;; :b1 has priority over :b2
```

## The step function is the core
At the heart of Pavlov’s bthread story is the step function. This is not a feature of standard behavioral programming but a Pavlov-specific convention that enables a number of capabilities.

A step function is a pure function that receives the previous state of the bthread and the event selected by the bprogram's algorithm, and returns the new state plus the next bid (a map with `:request`, `:wait-on`, and `:block`).

`tech.thomascothran.pavlov.bthread/step` wraps such a function with the plumbing the behavioral program expects.

There are convenience functions to create bthreads which have some nice advantages. If one of those other bthread functions meets your needs, prefer it to using a step function directly.

```clojure
(require '[tech.thomascothran.pavlov.bthread :as b])

(defn make-three-ticks
  []
  (b/step
   (fn [state _event]
     (cond
       (nil? state)
       [0 {:wait-on #{:tick}}]

       (= state :finished)
       [:finished nil]

       (< state 2)
       [(inc state) {:wait-on #{:tick}}]

       :else
       [:finished {:request #{{:type :counter/done
                               :terminal true}}}]))))
```

Stepping the bthread at the REPL shows the complete lifecycle:

```clojure
(let [three-ticks (make-three-ticks)]
  [(b/notify! three-ticks nil)
   (b/notify! three-ticks {:type :tick})
   (b/notify! three-ticks {:type :tick})
   (b/notify! three-ticks {:type :tick})
   (b/notify! three-ticks {:type :counter/done})])
;; => [{:wait-on #{:tick}}
;;     {:wait-on #{:tick}}
;;     {:wait-on #{:tick}}
;;     {:request #{{:type :counter/done, :terminal true}}}
;;     nil]
```

On initialization the bthread announces that it cares about `:tick`. After three ticks it requests a terminal `:counter/done` event and then yields `nil`, which deregisters the bthread.

## Helper constructors

Writing step functions directly is flexible but verbose. Pavlov provides convenience constructors that build common bthread patterns on top of `step`. For new scripted behavior, prefer `b/scenario`.

### `b/scenario` — scripted behavior

Use `b/scenario` for linear behavior. The bthread walks its steps in order and removes itself when the sequence is exhausted. Literal bid steps preserve state and advance.
Function steps receive `{:event event :state state}` and return a result map:

```clojure
(b/scenario
 [{:wait-on #{:order/placed}}
  (fn [context]
    (let [event (get context :event)
          state (get context :state)]
      {:bid {:request #{{:type :payment/requested
                         :order-id (:order-id event)}}}
       :state (assoc state :order-id (:order-id event))}))
  (fn [context]
    (let [state (get context :state)]
      {:bid {:request #{{:type :workflow/completed
                         :order-id (:order-id state)}}}
       :next-step :first}))]
 {:initial-state {}})
```

`:bid` is required. `:state` is optional and preserves the prior value when
absent; a present `:state nil` clears it. `:next-step` may be `:current` or
`:first` and takes effect on the next notification. With no directive the
scenario advances, and a nil bid terminates it.

The cursor and local state are both included in `b/state`, so state restoration
and model checking can distinguish scenario positions. Keep changing state
bounded in repeating scenarios to avoid an unbounded model-checking state space.

A scenario without function steps is simply a finite sequence of bids:

```clojure
(defn make-staged-requests
  []
  (b/scenario [{:request #{:prep/begin}}
               {:request #{:prep/finish}}
               {:request #{:ship}}]))

(let [staged-requests (make-staged-requests)]
  [(b/notify! staged-requests nil)
   (b/notify! staged-requests :prep/begin)
   (b/notify! staged-requests :prep/finish)
   (b/notify! staged-requests :ship)])
;; => [{:request #{:prep/begin}}
;;     {:request #{:prep/finish}}
;;     {:request #{:ship}}
;;     nil]
```

### `b/bids` — finite bid sequences

Existing code may use `b/bids` for finite sequences. Its function steps receive the selected event directly and return a bid directly, while `b/scenario` function steps receive a context map and return a result map containing `:bid`. Prefer `b/scenario` in new code so state and cursor control can be added without changing constructors.

### `b/after-all` — wait for several prerequisites

`b/after-all` coordinates independent event sources. It waits until every event type in the provided set has occurred (in any order) before forwarding to the supplied function.

```clojure
(defn make-ready-when-packed
  []
  (b/after-all #{:payment/authorized :packing/completed}
               (fn [events]
                 (let [order-id (->> events (keep :order/id) first)]
                   {:request #{{:type :order/ready
                                :order/id order-id
                                :sources (mapv :type events)}}}))))

(let [ready-when-packed (make-ready-when-packed)]
  [(b/notify! ready-when-packed nil)
   (b/notify! ready-when-packed {:type :packing/completed :order/id 42})
   (b/notify! ready-when-packed {:type :payment/authorized :order/id 42})
   (b/notify! ready-when-packed {:type :order/ready :order/id 42})])
;; => [{:wait-on #{:packing/completed :payment/authorized}}
;;     {:wait-on #{:packing/completed :payment/authorized}}
;;     {:request #{{:type :order/ready,
;;                  :order/id 42,
;;                  :sources [:packing/completed :payment/authorized]}}}
;;     nil]
```

Once all prerequisites are satisfied the bthread emits its completion request and then terminates.


Bthreads give you a lightweight way to isolate behavior into independent units. Understanding how they consume events, produce bids, and leverage helper constructors makes it straightforward to model complex coordination without entangling logic or state between components.
