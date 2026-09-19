# clj-statecharts integration

`tech.thomascothran.pavlov.clj-statecharts` adapts
[clj-statecharts](https://github.com/lucywang000/clj-statecharts) to Pavlov.
It is an optional namespace. Pavlov's core dependency manifest and ordinary
bthread namespace do not depend on clj-statecharts. Applications using this
integration must add:

```clojure
clj-statecharts/clj-statecharts {:mvn/version "0.1.7"}
```

This repository includes it only in the `:dev` alias.

## Bids live in the chart

Use ordinary clj-statecharts definitions, adding `::sc/bid` to any state node.
A bid can be a literal Pavlov bid or a pure function of the complete runtime
chart state (including `:_state` and the application's context fields).

```clojure
(require '[statecharts.core :as fsm]
         '[tech.thomascothran.pavlov.clj-statecharts :as sc]
         '[tech.thomascothran.pavlov.bthread :as b])

(def job-chart
  {:id :jobs
   :initial :idle
   :context {:job-id nil}
   :states
   {:idle
    {:on {:job/submitted
          {:target :requesting
           :actions (fsm/assign
                     (fn [state event]
                       (assoc state :job-id (:job-id event))))}}}

    :requesting
    {::sc/bid (fn [state]
                {:request #{{:type :job/execute
                             :job-id (:job-id state)}}})
     :on {:job/execute
          {:guard (fn [state event]
                    (= (:job-id state) (:job-id event)))
           :target :running}}}

    :running
    {:on {:job/completed
          {:guard (fn [state event]
                    (= (:job-id state) (:job-id event)))
           :target :idle}}}}})

(defn job-worker [] (sc/bthread job-chart))

(let [worker (job-worker)]
  (b/notify! worker nil)
  ;; => {:request #{} :wait-on #{:job/submitted} :block #{}}

  (b/notify! worker {:type :job/submitted :job-id 42})
  ;; => {:request #{{:type :job/execute :job-id 42}}
  ;;     :wait-on #{:job/execute} :block #{}}

  (b/notify! worker {:type :job/execute :job-id 42})
  ;; => {:request #{} :wait-on #{:job/completed} :block #{}}
  )
```

`b/notify!` is for illustration; a bprogram normally handles notification.
An environment handler performs the work requested by `:job/execute` and supplies
`:job/completed`. Requests remain offered until a transition or context update
removes them. A globally blocked request cannot advance the chart.

## How the adapter works

1. Strip `::sc/bid` from a copy of the definition and compile that copy with
   `fsm/machine`. Keep the original definition for bid lookup.
2. Initialize the chart or feed it a selected Pavlov event using `fsm/transition`.
3. After assignments and eventless transitions finish, identify active states.
4. Evaluate their bid definitions against the resulting state, combine them,
   and return the bid to Pavlov.

`sc/state->bid` is the general lookup function. It accepts the original chart
definition and the runtime state; applications do not implement it themselves.
`b/state` returns the runtime chart state and `b/set-state` restores it. A nil
notification recomputes a restored chart's bid without rerunning entry actions.
`sc/bthread` also accepts the usual `b/step` options, such as `:label`.

## Composition and scope

- The root, active ancestors, and all active parallel regions contribute bids.
  Parallel definitions use clj-statecharts' `:regions` key.
- Active `:on` event types are observed automatically, even if a guard is false.
  Guards prevent local transitions; only bids' `:block` prevents global selection.
- Waits and blocks are unioned. A child cannot override an ancestor's block.
- Multiple nonempty requests are unioned when they are sets. A single ordered
  request is preserved. Combining an ordered request with another request is
  rejected because flattening them would change Pavlov's priority semantics.
- `:hot` is true if any active contribution is hot. Child `:bthreads` maps are
  combined, rejecting duplicate names. These retain ordinary Pavlov spawning
  semantics: they are not state-scoped actors or automatically cancelled on exit.
- Nil/missing state bids contribute nothing; they do not terminate the chart.
  A configuration with no requests, waits, or blocks has no further activity in
  a Pavlov program. There is no additional final/history-state implementation.
- Pure `fsm/assign` actions execute normally. All guards, actions, and bid
  functions must be pure; external effects belong to environment event handlers.
- `:after` and `:scheduler` are rejected. Supply timer events through the
  environment. Transient states crossed by `:always` do not submit bids.
- The adapter uses the library's transition semantics, not a separate SCXML or
  XState implementation. Non-map events are normalized using Pavlov's event
  protocol; the original value is available as `::sc/event` to chart functions.

## Development tests

The optional CLJ/CLJS tests live in `modules/pavlov/test-integration`, on the
`:dev` classpath, so the default core test suite needs no optional dependency.
In a REPL started with `:dev`, run:

```clojure
(require 'tech.thomascothran.pavlov.clj-statecharts-test :reload)
(clojure.test/run-tests 'tech.thomascothran.pavlov.clj-statecharts-test)
```
