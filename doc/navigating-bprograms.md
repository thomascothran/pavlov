# Navigating Behavioral Programs (bprograms) via `tech.thomascothran.pavlov.nav`

This guide shows how to explore a Pavlov behavioral program interactively using Clojure’s `nav` concept and the helpers in `tech.thomascothran.pavlov.nav`.

Key tasks covered:
- Build a group of bthreads
- Make that group navigable
- Navigate through possible execution paths, including branches introduced by environment bthreads
- Inspect crumbs (history), branches (next choices), and bthread state snapshots

Important: This guide uses `nav` (from `clojure.datafy`) for navigation. `datafy` is not used.

## Concepts

- Behavioral Threads (bthreads): Independent units that advance by returning bids that request, wait-on, or block events.
- Environment bthreads: Bthreads that request events originating outside the system. They introduce branching by offering multiple possible next events.
- Branches: Alternative next events available at a state; these determine multiple successor states.
- Navigable node shape: A plain map with keys you can inspect and navigate through:
  - `:pavlov/event` – the event chosen at this node (may be `nil` at the root)
  - `:pavlov/path` – vector of events from root to this node
  - `:pavlov/branches` – a vector of branch maps for the next possible events
  - `:pavlov/crumbs` – prior nodes along the current path (for backtracking)
  - `:pavlov/bthreads` – snapshot of bthread state and selection data (see below)

Bthread state is snapshotted and restored for each branch. Side effects outside Pavlov (HTTP calls, DB writes, etc.) are not rolled back when you move around the execution graph.

## Quick Start (REPL)

Require the libs:

```clojure
(require '[tech.thomascothran.pavlov.nav :as pnav]
         '[tech.thomascothran.pavlov.bthread :as b]
         '[tech.thomascothran.pavlov.event :as e])
```

Create a small program with two bthreads: one that emits letters in order and one that emits numbers with a branch on the first step.

```clojure
(defn make-test-bthreads
  []
  {:letters (b/bids [{:request [:a]}
                     {:request [:b]}
                     {:request [:c]}])
   :numbers (b/bids [{:request #{1 2}} ; unordered → branch
                     {:request #{3}}])})
```

Turn the bthreads into a navigable root:

```clojure
(def root (pnav/root (make-test-bthreads)))
```

Inspect the available next steps (branch event-types):

```clojure
(->> (:pavlov/branches root)
     (mapv (comp e/type :pavlov/event)))
;=> [1 2 :a]
```

Navigate to the first number branch:

```clojure
(def at-1 (pnav/to root 1))
(-> at-1 :pavlov/event e/type)
;=> 1
```

See what comes next from here (the next branches):

```clojure
(->> (:pavlov/branches at-1)
     (mapv (comp e/type :pavlov/event)))
;=> [3 :a]
```

Follow a whole path by specifying the branch event-types at decision points. `follow` automatically advances through linear sections where there’s only one possible next event.

```clojure
(-> (pnav/follow root [1]) :pavlov/event e/type)
;=> 1

(-> (pnav/follow root [1 3]) :pavlov/event e/type)
;=> 3

(-> (pnav/follow (pnav/root {:linear (b/bids [{:request [:a]}
                                              {:request [:b]}
                                              {:request [:c]}])})
                 [:a :c])
    :pavlov/event e/type)
;=> :c

(-> (pnav/follow (pnav/root {:linear (b/bids [{:request [:a]}
                                              {:request [:b]}
                                              {:request [:c]}])})
                 [:a :d])
    :pavlov/event)
;=> nil  ; no matching branch at that decision point
```

## Reading Crumbs, Branches, Path, and Bthread State

- Crumbs are your backtrack history; the first crumb has a `nil` event (the root):

```clojure
(let [n1 (pnav/to root 1)
      n3 (pnav/to n1 3)]
  [(mapv (comp e/type :pavlov/event) (:pavlov/crumbs n3))
   (mapv (comp e/type :pavlov/event) (:pavlov/branches n3))
   (:pavlov/path n3)])
;=> [[nil 1] [:a] [1 3]]
```

- Bthread snapshot data available at each node:

```clojure
(keys (:pavlov/bthreads root))
;=> (:pavlov/bthread-states :pavlov/bthread->bid :pavlov/bthreads-by-priority)

(keys (get-in root [:pavlov/bthreads :pavlov/bthread-states]))
;=> (:letters :numbers)
```

The snapshot keeps per-bthread state stable per node as you navigate. External side effects are not part of this snapshot and won’t be reset when moving around.

## Branching with Environment Bthreads

Environment bthreads express possible inputs from outside the system and are the main way to introduce branches intentionally during exploration.

Branching patterns:
- Within a single bthread’s bid, use an unordered `set` of requested events to signal equal-priority alternatives for that bthread.

Example environment bthreads:

```clojure
(defn make-env-bthreads
  []
  {:submit      (b/bids [{:request #{{:type :application-submitted}}}])
   :pay-deposit (b/bids [{:request #{{:type :initial-deposit-paid}}}])})
```

Combine domain bthreads with environment bthreads in a single map (unordered keys imply equal priority among bthreads). The selection engine will:
- Collect unblocked bids from all unblocked bthreads (since the collection is unordered)
- For each selected bid, if the `:request` is a set, include each requested event as an alternative branch

Then explore with `pnav/root`, listing `:pavlov/branches` and stepping with `pnav/to` or `pnav/follow` as above.

Tip: If you want a single bthread to offer multiple alternatives at once, use a set in its `:request`. If you need multiple sources of alternatives (e.g., two independent environment influences), use a map for your bthreads so both bthreads are considered at a step.

## Practical Workflow for LLMs (clojure-mcp)

- Discover and require:
  - `tech.thomascothran.pavlov.nav` (entry points: `root`, `to`, `follow`)
  - `tech.thomascothran.pavlov.bthread` (helpers: `bids`, `on`, `thread`, etc.)
  - `tech.thomascothran.pavlov.event` (use `e/type` to read event types)
- Construct bthreads from examples or tests
- Build a navigable with `pnav/root`
- Inspect `:pavlov/branches` to see alternatives at any node
- Use `pnav/to` to pick a branch by event-type
- Use `pnav/follow` to automate through linear stretches, only specifying event-types at branch points
- Inspect `:pavlov/crumbs`, `:pavlov/path`, and `:pavlov/bthreads` to reason about history and state

All snippets in this guide are REPL-verified.

## Troubleshooting

- No branch found: `pnav/to` returns `nil` when there is no branch with the given event-type at the current node (or `pnav/follow` returns a node with `:pavlov/event` `nil` at the end).
- Unexpected single-path advancement: `pnav/follow` auto-advances when exactly one branch exists (even if the event is not in the list provided); you only need to provide event-types when there are multiple options.
- Side effects: Navigation keeps bthread state consistent per node but does not undo external side effects.
- Priority and ordering: Ordered collections favor a single highest-priority bthread and event; unordered collections (sets, maps) allow multiple alternatives and are useful for branching during exploration.

## References in Code

- Event selection and branching: `tech.thomascothran.pavlov.event.selection`
- Navigator construction and state snapshotting: `tech.thomascothran.pavlov.search`
- Navigation helpers: `tech.thomascothran.pavlov.nav`
- Bthread construction: `tech.thomascothran.pavlov.bthread`
- Examples and expectations: `test/tech/thomascothran/pavlov/nav_test.clj`
