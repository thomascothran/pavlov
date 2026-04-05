---
name: pavlov-bprograms
description: Understand the pavlov behavioral programming library. Use for using the library in production code, designing, debugging, or inspecting pavlov code.
---

# Pavlov bthreads and bprograms

Pavlov is a *behavioral programming* library. Behavioral programming was created by David Harel, Aasaf Marron, and Gera Weiss and is described [here](https://cacm.acm.org/research/behavioral-programming/).

Pavlov is a Clojure-specific take on functional programming. At is core it conceives of bthreads as step functions that is called by a bprogram notifier with that bthread program's last state and the event selected by the program (or `nil` on init), and returns a tuple of the next state and a bid. The bid requests, waits on, or blocks events. The bprogram is applies the algorithm to keep track of state per-bthread and selects the next event, notifying all bthreads subscribed to that event.

## Behavioral programming overview

- Bthreads are stateful units of behavior. They are notified of events and return bids that request, wait-on, or block events (or `nil` to terminate). See the docstring for `tech.thomascothran.pavlov.bthread)` for more details.
- Bprograms coordinate bthreads: select the next event, notify subscribed bthreads, and repeat until a terminal event or deadlock. See the docstring for `tech.thomascothran.pavlov.bprogram.ephemeral`.
- Event selection: collect bids, remove blocked events, choose the highest-priority bthread, then choose the highest-priority requested event. Ordered collections define priority; unordered collections introduce branching. See the docstring for `tech.thomascothran.pavlov.bprogram.ephemeral` and `tech.thomascothran.pavlov.event.selection`.


## Discovery workflow

Pavlov documents its behavior through

1. Markdown files provided as resources
2. docstrings at the namespace and function level. Namespace documentation provides an overview and examples.

### Discover documentation through docstrings

0. `(require '[clojure.repl :refer [doc source find-doc apropos]])`
1. `doc` the namespace.
2. `doc` the function you plan to use.
3. Use `source` if you need implementation detail.
4. Use `find-doc`/`apropos` to locate related helpers.

```clojure
(doc 'tech.thomascothran.pavlov.bthread)
(doc 'tech.thomascothran.pavlov.bthread/bids)
(source 'tech.thomascothran.pavlov.bthread/bids)
```

## REPL vs production

- REPL: use `let` bindings to explore stateful bthreads/bprograms.
- Production: always use constructor functions and avoid top-level `def` for bthreads, bprograms, or nav roots.

## Relevant namespaces

- `clojure.repl` for `doc`, `source`, `find-doc`, and `apropos`.
- `tech.thomascothran.pavlov.bthread` for bthread constructors and `notify!`.
- `tech.thomascothran.pavlov.bprogram.ephemeral` for `execute!` and `make-program!`.
- `tech.thomascothran.pavlov.bprogram` or `tech.thomascothran.pavlov.bprogram.proto` for `submit-event!`, `stop!`, and `subscribe!`.
- `tech.thomascothran.pavlov.nav` for REPL navigation (`root`, `to`, `follow`).
- `tech.thomascothran.pavlov.event` and use `event/type` instead of assuming events are maps.
- `tech.thomascothran.pavlov.subscribers.tap` for tap-based state inspection.
- Optional: `tech.thomascothran.pavlov.viz.portal` for Portal-based navigation.

## Reference guide

- `references/bprograms-repl.md` includes and runnable examples for bthreads, bprograms, `nav`, and tap subscribers.

## Write bthreads

- Define bthreads with constructor functions that return a bthread or bthreads; avoid `def` for bthread values.
  + Bthreads are stateful. Using `def` will produce unexpected behavior.
- Use bid maps with `:request`, `:wait-on`, and `:block`.
- Use ordered collections (vectors/lists) when priority matters; use unordered sets/maps to create branching.
- Prefer higher-level constructors first:
  - `b/on` for single-event reactions
  - `b/bids` for scripted sequences (supports bid functions)
  - `b/after-all` for prerequisites
  - `b/thread` for branching on event types
  - `b/step` only when you need full control

## Run a bthread at the REPL

- Use `b/notify!` with a `nil` event to initialize, then notify with events.

Never use `b/notify!` outside the context of inspecting a bthread in development. Bthreads in production run in bprograms.

## Compose and run a bprogram

Bprograms are stateful. For production code, do not define them under a `def`, instead use a constructor function.

- Compose bthreads as a map for non-deterministic priority, or as a vector of tuples for deterministic priority.
- Use `bprogram.ephemeral/execute!` for a fire-and-forget run that returns a promise of the terminal event.
- Use `bprogram.ephemeral/make-program!` when you want to submit events interactively and subscribe to state.

## Explore a bprogram with `nav`

- Use `pnav/root` to build a navigable program from a bthread collection.
- Use `pnav/to` to select a branch by event-type (or by predicate).
- Use `pnav/follow` to auto-walk linear paths and provide event-types only at branch points.
- Inspect `:pavlov/branches`, `:pavlov/crumbs`, `:pavlov/path`, and `:pavlov/bthreads` for state snapshots.
- Remember: navigation restores bthread state per node but does not undo external side effects.

See the namespace for `tech.thomascothran.pavlov.nav`, as well as the function docstrings for more details
