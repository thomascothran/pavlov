# clj-statecharts integration

Use [clj-statecharts](https://github.com/lucywang000/clj-statecharts) charts as
Pavlov bthreads through `tech.thomascothran.pavlov.clj-statecharts`.
This integration is experimental and its functionality is alpha.

To use it, add this optional dependency to your application:

```clojure
clj-statecharts/clj-statecharts {:mvn/version "0.1.7"}
```

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

Add `(job-worker)` to a bprogram just as you would any other bthread.
`b/notify!` above lets you explore the chart in the REPL; a bprogram normally
handles notification.
An environment handler performs the work requested by `:job/execute` and supplies
`:job/completed`. Requests remain offered until a transition or context update
removes them. A globally blocked request cannot advance the chart.

## Combining state bids

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
  a Pavlov program.

## Actions, timers, and eventless transitions

Use pure `fsm/assign` actions to update context. Guards, actions, and bid
functions must be pure. To trigger external work, request an event and let an
environment handler perform the effect, as with `:job/execute` above.

Supply timer events through the environment; `:after` and `:scheduler` are not
supported. For eventless transitions, bids reflect the state reached after the
`:always` transitions finish. A transient state cannot request work and expect
it to execute before its eventless transition leaves that state.

Chart transitions follow clj-statecharts 0.1.7 semantics. Its unsupported
features, including history and final/done states, are not added by this
integration.

## Model checking and state properties

Pass chart bthreads to Pavlov's model checker just like other bthreads. It
explores alternative events and restores each branch's chart state and context.
Provide environment bthreads for events the outside world can supply.

Two optional boolean markers express properties directly on states:

- `::sc/hot true`: progress is required while this state is active. The checker
  reports a liveness violation if execution can stay hot forever, deadlock while
  hot, or terminate while hot. Leaving the hot state discharges that obligation
  unless another active state or bid remains hot.
- `::sc/invariant-violated true`: this state must never be entered. Entry
  requests a terminal safety-violation event instead of ordinary work, even if
  an immediate `:always` transition leaves the forbidden state.

Markers on parents apply throughout their active descendants. Any active
parallel region can make the chart hot or violate an invariant. Use guarded
transitions into a marked state for a condition-dependent violation.

For example, this deliberately permits a failing path so the checker can find it:

```clojure
(require '[tech.thomascothran.pavlov.model.check :as check])

(check/check
 {:bthreads
  {:worker
   (sc/bthread
    {:id :checked-job
     :initial :working
     :states
     {:working {::sc/hot true
                :on {:finish :complete :fail :failed}}
      :complete {::sc/bid {:request #{{:type :done :terminal true}}}}
      :failed {::sc/invariant-violated true}}})}
  :environment-bthreads
  {:outcome (b/scenario [{:request #{:finish :fail}}])}
  :possible #{:done}})
;; Reports :safety-violations for the :fail path.
;; The successful :done event is also reachable.
```

The checker is in the optional `pavlov-devtools` module. Successful checks return
nil; failures include witnesses under keys such as `:safety-violations`,
`:liveness-violation`, and `:deadlocks`. Safety events identify this integration
with `:type ::sc/invariant-violated` and include the chart snapshot as `:state`.
Its `::sc/violated-states` field lists the forbidden state paths entered; preserve
this reserved field when updating context or restoring snapshots.

## Inspecting and restoring a chart

Use `(b/state worker)` to inspect the active state and context. To inspect its
bid, call `(sc/state->bid job-chart (b/state worker))` after initialization.
`b/set-state` restores a saved runtime state. A subsequent nil notification
returns its bid without rerunning entry actions.

`sc/bthread` accepts the usual `b/step` options, such as `:label`, as a second
argument. Chart functions receive map events with their payload intact. For
non-map Pavlov events, `:type` is the event's Pavlov type and `::sc/event` holds
the original value.
