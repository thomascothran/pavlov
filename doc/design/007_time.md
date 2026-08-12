# Time In Behavioral Programs

Status: Draft
Date: 2026-03-20

## Problem We Are Trying To Solve

Pavlov needs a first-class way for bthreads to express time in bid data.

The motivating case is a timeout: a bthread should be able to declare that if some other event does not happen first, then after a delay it wants to request another event.

This must work in all three of these places:

This must work in three places at once:
2. in the LTS built for exploration and visualization, and
3. in model checking.

The representation must be data-driven, must work on both the JVM and ClojureScript, and must not depend on external libraries.

## Context

### How Pavlov Works Today

Pavlov bthreads return bids that describe what they currently:

- request,
- wait on, and
- block.

The bprogram selects the next event from the currently requested events, subject to bthread priority and block semantics.

The exploration and model-checking path follows the same basic shape:

- `modules/pavlov/src/tech/thomascothran/pavlov/bprogram/state.cljc`
- `modules/pavlov/src/tech/thomascothran/pavlov/event/selection.cljc`
- `modules/pavlov-devtools/src/tech/thomascothran/pavlov/search.cljc`
- `modules/pavlov-devtools/src/tech/thomascothran/pavlov/graph.cljc`
- `modules/pavlov-devtools/src/tech/thomascothran/pavlov/model/check.clj`

Today, those paths only reason about requests that are selectable now.

### Why This Is Not Enough

Time cannot be treated as a purely runtime concern.

If timeouts are implemented only by scheduling external event injection in the runtime, then the runtime may appear to support timeouts, but the graph and model checker will not understand the same behavior. That would create a gap between the checked model and production behavior.

### The Design Tension

We need to support two different notions of time:

- logical time for search and model checking, and
- wall-clock time for running programs on CLJ and CLJS.

The design should let both arise from the same bid-level declaration.

## Criteria Of A Good Solution

A good solution should:

1. represent time in plain data returned by bthreads,
2. preserve existing bid semantics rather than bypassing them,
3. be model-checkable without introducing a separate formalism,
4. work on both the JVM and ClojureScript,
5. avoid external library dependencies,
6. support cancellation and rescheduling when bids change,
7. support more than one pending timer per bthread, and
8. keep the checked model and runtime behavior aligned.
9. handle cancellation
10. bonus: be implemented in a pluggable way with a bthread or a subscriber. If the bid could *declare* time properties and a subscriber or bthread handle the details, that would be ideal, but not strictly necessary.

## Constraints

### We Must Do

- keep time declarations in data,
- support the existing LTS and model-checking workflow,
- preserve priority and block semantics when timed requests become due,
- share the semantic model across CLJ and CLJS,
- keep the core time semantics portable and testable.

### We Must Not Do

- make time purely a runtime side effect,
- depend on external scheduling libraries,
- require users to model ordinary timeouts manually as low-level clock noise,
- introduce semantics that the model checker cannot represent,
- let CLJ and CLJS diverge in observable timeout behavior.

## High-Level Options

### Option A: Model Time As Ordinary `:tick` Events

Bthreads would wait on or react to clock events supplied by environment bthreads.

Pros:

- works with the current architecture,
- requires minimal new semantics.

Cons:

- poor timeout ergonomics,
- too low-level for common usage,
- likely state-space blowup,
- pushes a core concern onto user boilerplate.
- how many bthreads are waiting on tick events?

### Option B: Implement in Core

Bids would declare delayed requests, and graph/check tooling would jump directly to the future timeout event as though it were immediately reachable.

### Option C: Standardize the bthread data structure, but leave implementation to a subscriber

This has the benefit of making implementations swappable.

## Desired Bid-Level API

The canonical representation should be richer than a bare `milliseconds -> set-of-events` map.

We want timer identity, support for multiple timers, and a way for timed
requests to preserve existing request semantics.

The preferred shape is:

```clojure
{:wait-on #{:reply}
 :request-after [{:id :reply-timeout
                  :after-ms 5000
                  :request #{{:type :reply-timeout}}}]}
```

This shape is preferable because:

- `:id` gives stable identity for cancellation and rescheduling,
- `:after-ms` is explicit,
- `:request` can preserve existing set/vector request semantics,
- a vector supports multiple timers, including multiple timers with the same
  delay.

The simpler `milliseconds -> events` form may still be offered as user-facing
syntax sugar, but it should not be the canonical internal model.

To prevent the bthreads communicating through the scheduler, the implementation probably needs to scope the timer id to the bthread id.

## Logical Time In Search And Model Checking

Search and model checking should use logical time, not wall clock time.

That means timer state must become part of the explored state identity.

Successor generation should include two kinds of transitions:

1. ordinary event transitions for currently selectable events,
2. a logical time-advance transition that advances time to the earliest pending
   deadline and materializes any due timed requests.

For v1, time should advance only when there is no ordinary selectable event.
This keeps timeout behavior consistent with the existing rule that if an event
is currently requestable, the bprogram selects among current requests rather
than waiting for time to pass.

The important point is that the checker should not skip directly from "waiting"
to "timeout event selected" without an intermediate logical step in which the
request becomes due.
