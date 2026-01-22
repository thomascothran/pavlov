---
name: pavlov-model-checking
description: Model checking for Pavlov behavioral programs. Use when testing Pavlov features, working with existing model-checking tests, or driving development by defining the model and properties first.
---

# Pavlov model checking

Pavlov model checking explores all reachable event traces for a set of bthreads, reporting deadlocks, livelocks, safety violations, and liveness violations. It is designed for model-first development: specify the behavior and properties, run the checker, then iterate on implementation.

## Terminology

### Safety properties

Safety properties say "something bad never happens." In Pavlov, safety checks are defined as *safety bthreads* and passed under `:safety-bthreads`. They monitor the trace and emit an event with `:invariant-violated true` when the rule is broken.

### Liveness properties

Liveness properties say "something good eventually happens." In Pavlov, liveness checks are expressed alongside the model (conceptually like bthreads that inspect traces) using the `:liveness` map passed to `check/check`.

- **Universal** (`:quantifier :universal`) means the event or predicate must hold on *every* path (aka the trace).
- **Existential** (`:quantifier :existential`) means the event or predicate must hold on *at least one* path (aka the trace).

### Deadlocks

A deadlock means no events are possible and no terminal event occurred. Deadlocks matter when you expect the model to make progress or terminate. If you are modeling an open system, you can add environment bthreads or disable `:check-deadlock?` while refining the model.

### Livelocks

A livelock means the model cycles forever without reaching a terminal event. Livelocks matter when progress should eventually happen; if cycles are intentional, consider `:check-livelock? false` or add a terminal event to represent completion.

## Choose your path

- Building or updating a model? See ./references/designing-models.md
- Need API details or config options? Use docstrings.
- Need concrete model-checking examples? Read the tests.
- Need help designing models and properties? Use the modeling reference.
- Need to understand results? Use the interpretation reference.

## Model-first workflow

1. Define bthread factories that return fresh instances per run.
2. Specify properties:
   - safety bthreads that emit `:invariant-violated true`
   - liveness properties via the `:liveness` map
3. Run `tech.thomascothran.pavlov.model.check/check` with your config map.
4. Interpret the result using `references/interpret-results.md` (resource `skills/pavlov-model-checking/references/interpret-results.md`).
5. Iterate on model and properties before implementation details.

## Where to look

- Docstrings: `clojure.repl/doc` on `tech.thomascothran.pavlov.model.check/check`.
- Tests (canonical examples): resource `tech/thomascothran/pavlov/model/check_test.clj`.
- Repo path: `modules/pavlov-devtools/test/tech/thomascothran/pavlov/model/check_test.clj`.
- Modeling guidance: resource `skills/pavlov-model-checking/references/designing-models.md`.
- Result interpretation: resource `skills/pavlov-model-checking/references/interpret-results.md`.
- REPL setup: resource `skills/pavlov-model-checking/references/model-checker-repl.md`.
