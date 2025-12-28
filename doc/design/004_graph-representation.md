# Graph Representation for Behavioral Program Visualization

Created: December 27, 2025

## Context

The `tech.thomascothran.pavlov.graph` namespace provides graph construction for visualizing behavioral program execution paths. This graph is used for:

1. **Debugging** - understanding how bthreads interact and what states are reachable
2. **Demo/Communication** - showing stakeholders the possible execution paths

The graph is NOT used for model checking (that's handled separately in `tech.thomascothran.pavlov.model.check`).

### The Problem

There is an ambiguity in how to represent the relationship between **BP program states** and **events**:

- A BP program state contains: bthread states, bids, blocks, `:last-event` (processed), and `:next-event` (selected but not yet processed)
- Events are the transitions that move between states
- **Multiple paths can lead to the same state** (convergence), and we want to visualize this

The current implementation uses **event paths** (e.g., `[:a :b :c]`) as node identifiers, which conflates event history with state identity. This causes problems:

1. Two paths to the same state produce different nodes (no visual convergence)
2. Node identity is tied to history rather than current configuration
3. The `identifier` function correctly captures state equivalence, but isn't used as the primary node ID

### What Should Be a Node? What Should Be an Edge?

Standard automata theory and model checking use **Labeled Transition Systems (LTS)**:
- **Nodes** = States (system configurations)
- **Edges** = Events (labeled transitions)

However, users think in terms of events ("what happened"), not states ("what's the current configuration"). This creates tension between semantic correctness and usability.

## Constraints

1. **Convergence must work** - Same state reached via different paths should be one node
2. **Events are primary for users** - Labels should emphasize events, not state internals  
3. **State details must be accessible** - For debugging, full state info needed in popovers
4. **Root node is special** - Has no `:last-event` but has valid state; label it "init"

## Options

### Option A: Event Paths as Node IDs (Current Approach)

```
Node ID:   [:a :b]  (the path of events)
Node data: {:path [...], :event :b, :wrapped {...}}
Edge:      {:from [:a], :to [:a :b], :event :b}
```

**Pros:**
- History is explicit in the ID
- Easy to understand what path led here

**Cons:**
- No natural convergence ([:a :b] ≠ [:c :b] even if same state)
- Exponential node growth in branching programs
- Conflates "how we got here" with "where we are"

### Option B: State Identifiers as Node IDs (Semantic Correctness)

```
Node ID:   (hash-of [bthread-states bthread->bid last-event-terminal])
Node data: {:identifier ..., :state {...}, :incoming-events #{...}}
Edge:      {:from id-1, :to id-2, :event :b}
```

**Pros:**
- Correct semantics (same state = same node)
- Natural convergence
- Matches model checking representation

**Cons:**
- Node IDs are opaque hashes
- Need to derive display labels from state

### Option C: Separate Logical and Display Layers (Hybrid)

**Logical layer** (`graph.cljc`):
- Node ID = state identifier (for convergence)
- Node contains full state + metadata
- Edges = event transitions

**Display layer** (`cytoscape.clj`):
- Node label = `:last-event` (or "init" for root)
- Node popover = full state details, all incoming paths
- Visual convergence where edges from different sources meet at one node

```
Logical:
  Node: {:id "state-abc123"
         :state {...}
         :incoming-events #{:a :c}   ;; multiple events can lead here
         :outgoing-events #{:d :e}}
  Edge: {:from "state-xyz", :to "state-abc123", :event :a}

Display:
  Node label: ":b" (the 'canonical' incoming event, or derived)
  Popover: full state, all paths that reach here
```

**Pros:**
- Semantically correct graph structure
- User-friendly display
- Convergence visualized naturally
- Clean separation of concerns

**Cons:**
- More complex implementation
- Need to decide how to label nodes when multiple events converge

## Decision

**Option C: Separate Logical and Display Layers**

The graph should be constructed with **states as nodes** (identified by the existing `identifier` function) and **events as edges**. The display layer transforms this into user-friendly visualization where:

1. Nodes are labeled by their "primary" incoming event (or "init" for root)
2. Convergence is shown visually (multiple edges entering one node)
3. Full state details are available in popovers
4. Paths/history can be reconstructed from edges

### Function Naming

The primary graph construction function will be named **`->lts`** (Labeled Transition System) to:
- Accurately reflect the graph semantics
- Leave room for alternative graph representations in the future (e.g., `->trace-graph` for event-path-based views)

### Node Labeling Strategy for Convergence

When multiple events lead to the same state:
- Use the `:last-event` as the label (this is well-defined per state)
- In the popover, show all incoming edges/events
- The visual convergence (multiple arrows pointing to one node) communicates the branching

### Implementation Changes Required

1. **`graph.cljc`**: Rename `->graph` to `->lts`; use `identifier` as node ID, not path
2. **`graph.cljc`**: Track incoming edges per node for convergence info
3. **`cytoscape.clj`**: Update to use `->lts`; derive labels from `:last-event`, handle "init" case
4. **Tests**: Update to expect convergent nodes where appropriate
