# Liveness Properties and Livelock Detection

Created: December 28, 2025

## Context

The model checker in `tech.thomascothran.pavlov.model.check` currently verifies **safety properties** — it detects states that should never be reached (via `:invariant-violated` events) and deadlocks (no selectable events, no terminal event).

Safety properties answer: *"Does the program never do something bad?"*

We now want to extend the model checker to verify **liveness properties** — guarantees that something good *eventually* happens. We also want to detect **livelocks** — situations where the program runs forever without making meaningful progress.

Liveness properties answer: *"Does the program eventually do something good?"*

### Terminology

| Term | Definition |
|------|------------|
| **Safety property** | A condition that must hold in every reachable state. Violated by reaching a bad state. |
| **Liveness property** | A condition that must eventually be satisfied. Violated by traces where the condition is never met. |
| **Deadlock** | The program cannot proceed — no events can be selected and no terminal event occurred. |
| **Livelock** | The program runs forever (is in a cycle) but never reaches a terminal state. |
| **Terminal state** | A state where the last event had `:terminal true`. The program has completed. |
| **Trace** | A sequence of events from the initial state. |
| **Cycle** | A path in the state graph that returns to a previously visited state. |
| **SCC** | Strongly Connected Component — a maximal set of states where every state is reachable from every other. |

### Relationship to Existing Infrastructure

- **`tech.thomascothran.pavlov.graph/->lts`**: Constructs a Labeled Transition System (LTS) from bthreads. Nodes are states (identified by bthread states + bids), edges are events.
- **`tech.thomascothran.pavlov.search`**: Provides BFS/DFS traversal with cycle detection via state identifiers.
- **`tech.thomascothran.pavlov.model.check`**: Current safety checker using BFS traversal.

The LTS graph structure is well-suited for both livelock detection (finding trapped cycles) and liveness checking (analyzing paths through the graph).

## Problems to Solve

### Problem 1: Livelock Detection

**Goal**: Detect when the behavioral program can get stuck in an infinite loop — it keeps running but never terminates.

**Definition**: A livelock exists when there is a reachable cycle in the state graph with no path from that cycle to a terminal state.

**Example**:
```
State A → State B → State C → State A (cycle)
         ↓
       State D (terminal)
```
This is NOT a livelock — the cycle has an escape path to terminal state D.

```
State A → State B → State C → State A (cycle, no escape)
```
This IS a livelock — once in the cycle, the program can never terminate.

**Important Consideration**: Some cycles are intentional and valid. For example:
- A server that handles requests in a loop
- A monitoring process that periodically checks status

**Flag for Design**: How do we distinguish "good" infinite loops from livelocks? Options:
1. **Configuration**: User specifies whether cycles are expected (`:allow-cycles? true`)
2. **Bounded checking**: Add an environment bthread that terminates after N iterations (for model checking only)
3. **Property-based**: Only flag cycles that violate a liveness property

For the initial implementation, we can detect all inescapable cycles and let the user interpret the results. The bounded-checking approach (adding a bthread that terminates after N iterations during model checking) is a pragmatic workaround.

### Problem 2: Liveness Property Specification

**Goal**: Allow users to specify properties of the form "eventually X happens."

**Scope**: Liveness properties are global (not per-bthread). They constrain the system's behavior without requiring the constrained bthreads to know about them. This aligns with behavioral programming's philosophy of loose coupling.

**Two Quantifiers**:

| Quantifier | Meaning | Violation Condition |
|------------|---------|---------------------|
| **Universal (∀)** | X must eventually happen on ALL paths | Any path (including cycles) that never satisfies X |
| **Existential (∃)** | X must eventually happen on SOME path | NO path satisfies X |

This follows standard CTL (Computation Tree Logic) conventions:
- Universal liveness = `AF p` ("for All paths, Finally p")
- Existential liveness = `EF p` ("Exists a path where Finally p")

**Property Format Options**:

#### Option A: Trace Predicate Functions

```clojure
{:verify-liveness
 {:payment-received
  {:quantifier :universal  ;; or :existential
   :predicate (fn [trace]
                (some #(= :payment-received (e/type %)) trace))}}}
```

The predicate receives the full trace (sequence of events from initial state) and returns true if the property is satisfied.

**Pros**:
- Maximum flexibility — any trace property expressible
- Simple mental model

**Cons**:
- Can't evaluate on infinite traces directly (cycles)
- Algorithm must handle cycles specially

#### Option B: Event-Based Shorthand

```clojure
{:verify-liveness
 {:payment-received
  {:quantifier :universal
   :eventually #{:payment-received}}}}  ;; satisfied when this event occurs
```

For the common case where "eventually X" means "event X occurs."

**Pros**:
- Simpler to specify
- Easy to check incrementally
- Handles cycles naturally (check if event is in cycle)

**Cons**:
- Less expressive than predicates

#### Option C: Both (Recommended)

Support both forms. The event-based shorthand is sugar for the common case:

```clojure
;; These are equivalent:
{:eventually #{:payment-received}}

{:predicate (fn [trace] (some #(= :payment-received (e/type %)) trace))}
```

For cycles, the algorithm:
- For event-based: Check if the event appears in the cycle
- For predicates: Check if any state in the cycle would satisfy the predicate

#### Extension: Allow bthreads to declare and satisfy liveness properties

Once we have the trace information, we can extend bids to allow bthreads to declare:

- What liveness properties should be satisfied, and
- What liveness properties have been satisfied

For example, a bid could be something like:

```clojure
{:request #{:a}
 :verify-liveness {:payment-received {:quantifier :existential}}
```

And then when that property is satisfied, this (or another) bthread could declare:

```clojure
{:request #{:b}
 :liveness-satisfied #{:payment-received}}
```

### Problem 3: Liveness Checking Algorithm

**Goal**: Given a set of liveness properties, determine if they are satisfied.

**For Terminating Paths** (paths that reach a terminal state):
1. Run the trace predicate on the full trace
2. If predicate returns false, the property is violated on this path

**For Non-Terminating Paths** (cycles/livelocks):
1. Find all cycles reachable from the initial state (SCCs with no terminal escape)
2. For each cycle, check if the property can be satisfied within the cycle
3. If not, the property is violated (the system can loop forever without satisfying it)

**Universal vs Existential**:
- **Universal**: Property must be satisfied on ALL paths. Report violation if ANY path (terminating or cycle) fails.
- **Existential**: Property must be satisfied on SOME path. Report violation only if ALL paths fail.

**Algorithm Sketch**:

```
1. Build LTS from bthreads
2. Find all terminal states
3. Find all SCCs (Tarjan's algorithm)
4. Identify "trapped" SCCs (no path to terminal state)
5. For each liveness property:
   a. For universal mode:
      - Check all terminating paths — any violation?
      - Check all trapped cycles — does property get satisfied in cycle?
   b. For existential mode:
      - Check if ANY path (terminating or cycle) satisfies property
```

### Problem 4: Unsatisfied Termination

**Goal**: Detect when the program terminates (reaches terminal state) without satisfying a liveness property.

This is a liveness violation for **universal** properties: if the property says "X must eventually happen on all paths" and a path terminates without X, that's a violation.

For **existential** properties, a single path terminating without X is not a violation — only if NO path satisfies X.

**This is part of liveness checking, not a separate category.**

## How Other Systems Handle This

### BPjs (Behavioral Programming for JavaScript)

**Approach**: Per-sync-point "hot" markers

```javascript
bp.hot(true).sync({waitFor: responseFor(req)});
```

A bthread marks a synchronization point as "hot" — meaning it must eventually advance past it.

**Violation Types**:
- **Hot Run**: A group of bthreads cycles while at least one is always hot
- **Hot Termination**: Program terminates while a bthread is hot

**Algorithm**: DFS traversal. On cycle detection, check if any bthread is hot at every state in the cycle.

**Assessment for Pavlov**: The "hot" approach couples the liveness requirement to the bthread itself. Our global property approach is more aligned with BP's loose coupling philosophy — a bthread shouldn't need to know if its request is "required" to eventually succeed.

### TLA+

**Approach**: Temporal logic operators

```
[]P      — Always P (invariant)
<>P      — Eventually P (liveness)
<>[]P    — Eventually always P (convergence)
[]<>P    — Always eventually P (recurrence)
P ~> Q   — P leads to Q (if P then eventually Q)
```

**Fairness**: TLA+ allows infinite stuttering (crash at any time). Must declare weak/strong fairness to prevent spurious liveness violations.

**Algorithm**: Nested DFS with Büchi automata for accepting cycle detection.

**Assessment for Pavlov**: TLA+'s temporal operators are very expressive but complex. Our trace-predicate approach is simpler while covering the common cases. We can add temporal operators later if needed.

### SPIN Model Checker

**Approach**: Linear Temporal Logic (LTL) formulas compiled to Büchi automata

**Algorithm**: Nested DFS to find accepting cycles (cycles where the negation of the property is satisfied infinitely often).

**Assessment for Pavlov**: SPIN's approach is academically rigorous but complex to implement. Our SCC-based approach is simpler and sufficient for behavioral programs.

## Design Options

### Option 1: Livelock Detection Only (Starting Point)

Implement livelock detection as a separate, simple check:

```clojure
(defn find-livelocks
  "Detect cycles with no path to a terminal state.

  Returns nil if no livelocks, or a map with:
    :type :livelock
    :cycle - the sequence of states forming the cycle
    :path - path from initial state to the cycle"
  [config]
  ...)
```

**Pros**:
- Simpler to implement
- Immediately useful
- Doesn't require property specification

**Cons**:
- Doesn't tell you *why* the cycle is bad
- May report false positives for intentional loops

### Option 2: Full Liveness Checking

Extend `check` with the full liveness property system:

```clojure
(check
  {:bthreads ...
   :liveness
   {:payment-received
    {:quantifier :universal
     :eventually #{:payment-received}}

    :order-fulfilled
    {:quantifier :existential
     :predicate (fn [trace]
                  (some #(= :order-shipped (e/type %)) trace))}}})

;; Returns nil if all checks pass, or:
;;   {:type :liveness-violation
;;    :property :payment-received
;;    :quantifier :universal
;;    :trace [...] - counterexample trace
;;    :cycle [...] - cycle info if violation is in a cycle}
```

**Pros**:
- Full expressiveness
- Distinguishes intentional loops from violations

**Cons**:
- More complex implementation
- Requires property specification from user

### Option 3: Phased Implementation (Recommended)

Implement in phases, but always within the unified `check` function:

1. **Phase 1**: Livelock detection
   - Add `:check-livelock?` option to `check`
   - Detect inescapable cycles
   - Simple, structural check
   - Useful immediately

2. **Phase 2**: Liveness properties
   - Add `:liveness` option to `check`
   - Universal/existential quantifiers
   - Build on Phase 1's cycle detection

The unified API is stable from Phase 1; only the implementation grows.

## API Design Considerations

### Unified `check` Function

The `check` function should handle safety, deadlock, livelock, and liveness checks in a unified interface. Internal helper functions can handle the specifics of each check type.

```clojure
(check {:bthreads ...
        :safety-bthreads ...
        :environment-bthreads ...

        ;; Existing options
        :check-deadlock? true          ;; default true

        ;; New options
        :check-livelock? true          ;; default true
        :liveness {...}                ;; liveness properties (optional)
        })
```

**Rationale**: Users shouldn't need to call multiple functions to verify their program. A single `check` with configuration options is simpler and more discoverable.

**Internal Structure**: The implementation can use private helper functions:
- `check-safety` — current violation detection
- `find-livelocks` — cycle detection
- `check-liveness` — liveness property verification

These are implementation details, not part of the public API.

### Trace Function Signature

For trace predicates, the trace should be the full sequence of events from initial state:

```clojure
(fn [trace]
  ;; trace is a vector of events, e.g., [{:type :a} {:type :b} {:type :c}]
  (some #(= :payment (e/type %)) trace))
```

**Question**: Should the predicate also receive state information, or just events?

**Recommendation**: Start with events only. State can be reconstructed if needed, and events are the primary abstraction users work with.

## Implementation Considerations

### Cycle Detection in LTS

The current `->lts` function builds the graph but doesn't identify cycles. We need:

1. **Tarjan's algorithm** for finding SCCs
2. **Reachability analysis** from each SCC to terminal states
3. **Path reconstruction** for counterexample reporting

### Performance

For large state spaces:
- SCC detection is O(V + E)
- Reachability analysis is O(V + E) per SCC
- Overall still linear in graph size

The existing `max-nodes` limit in `->lts` can prevent runaway exploration.

### Counterexample Quality

For liveness violations, the counterexample should include:
- The path from initial state to the violation
- For cycles: the cycle itself
- The property that was violated

## Open Questions

1. **Cycle tolerance**: How should users indicate that certain cycles are intentional? Configuration flag? Special bthread? Property-based?

2. **Fairness**: Should we support fairness constraints? (Set aside for now per discussion, but may be needed for accurate livelock detection.)

3. **Composition**: Can liveness properties compose? (e.g., "A eventually implies B eventually")

4. **Runtime checking**: Should liveness properties be checkable at runtime (not just model checking)? BPjs supports this via runtime assertions on hot states.

## Decision

**Proceed with the phased implementation within a unified `check` function**:

1. **Phase 1**: Add `:check-livelock?` option for structural cycle detection
2. **Phase 2**: Add `:liveness` option with trace predicates and event shorthand, supporting `:quantifier :universal` and `:quantifier :existential`

Start with Phase 1 as it's simpler and immediately useful. The API remains stable across phases.
