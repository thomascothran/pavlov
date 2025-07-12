# Model Checker State Capture and Restoration Plan

## Background

Pavlov is a behavioral programming library that implements the paradigm described in the paper "Behavioral Programming" (Harel et al.). The library enables event-driven programming through behavior threads (bthreads) that coordinate through a behavior program (bprogram).

Currently, the model checker implementation in `tech.thomascothran.pavlov.check` uses a brute-force approach that re-executes the entire program for each path exploration. This is inefficient and doesn't scale well for complex programs with many possible execution paths.

A proper model checker needs to:
1. Explore different execution paths systematically
2. Detect cycles to avoid infinite exploration
3. Backtrack to previous states efficiently
4. Check safety and liveness properties

The key challenge is implementing efficient state capture and restoration to enable backtracking without re-execution.

## Problem Statement

### Core Challenge
To implement an efficient model checker, we need to capture the complete state of a behavioral program at any point and restore it later. This requires:

1. **BThread State**: Each bthread maintains internal state through volatile references
2. **BProgram State**: The coordination state includes:
   - Current bids from each bthread
   - Event queues (requests, waits, blocks)
   - Priority ordering
   - Last processed event

### Technical Challenges

1. **Stateful BThreads**: BThreads use volatile references for state management, making direct serialization complex
2. **Object References**: The bprogram state contains direct object references to bthreads in its event maps
3. **Side Effects**: Getting a bid from a bthread may advance its internal state
4. **Different BThread Types**: Various bthread implementations (step, bids, etc.) have different serialization formats
5. **Deterministic Recreation**: Need to ensure bthreads can be recreated identically

### Current Implementation Issues

1. **Serialization Bug**: The `bids` bthread implementation incorrectly serializes the original sequence instead of the current position
2. **State Initialization**: The `state/init` function calls `bid` with `nil` event, causing unwanted state advancement
3. **No Checkpoint/Restore**: No existing mechanism for state capture and restoration

## Relevant Files

### Core Protocol Definitions
- `src/tech/thomascothran/pavlov/bthread/proto.cljc`: BThread protocol with serialize/deserialize methods
- `src/tech/thomascothran/pavlov/bprogram/proto.cljc`: BProgram protocol

### BThread Implementations
- `src/tech/thomascothran/pavlov/bthread.cljc`: Core bthread functions (step, bids, etc.)
- `src/tech/thomascothran/pavlov/bthread/defaults.cljc`: Default protocol implementations

### State Management
- `src/tech/thomascothran/pavlov/bprogram/ephemeral/state.cljc`: BProgram state management
- `src/tech/thomascothran/pavlov/event/selection.clj`: Event selection logic

### Model Checker
- `src/tech/thomascothran/pavlov/check.clj`: Current model checker implementation

## Discovered Insights

### 1. BThread Serialization
- The `serialize` method returns internal state data
- The `deserialize` method mutates an existing bthread instance (doesn't create new ones)
- Step bthreads correctly serialize their state value
- Bids bthreads have a bug: they serialize the original sequence, not current position

### 2. BProgram State Structure
```clojure
{:bthread->bid       ; Map of bthread-name to current bid
 :bthreads-by-priority ; Ordered list of bthread names
 :last-event        ; Last processed event
 :next-event        ; Next selected event
 :requests          ; Map of event-type to set of bthreads
 :waits            ; Map of event-type to set of bthreads  
 :blocks           ; Map of event-type to set of bthreads}
```

### 3. Key Insight
The bprogram state already stores the current bids in `:bthread->bid`. We don't need to regenerate bids during restoration, avoiding potential non-determinism.

## Proposed Solution

### Overview
Use the existing `make-bthreads` function as a factory for creating fresh bthread instances, then restore their internal state and use the saved bids to reconstruct the bprogram state.

### State Capture Process

```clojure
(defn capture-model-checker-state
  [bprogram-state bthreads]
  {:bthread-states (into {} (map (fn [bt] 
                                  [(b/name bt) (b/serialize bt)])
                                bthreads))
   :bthread->bid (:bthread->bid bprogram-state)  ; Current bids!
   :last-event (:last-event bprogram-state)
   :bthreads-by-priority (:bthreads-by-priority bprogram-state)})
```

1. Serialize each bthread's internal state
2. Save the current bid mapping (avoiding regeneration)
3. Save the last event and priority ordering

### State Restoration Process

```clojure
(defn restore-model-checker-state
  [captured-state make-bthreads]
  (let [; Create fresh bthreads using the factory
        fresh-bthreads (make-bthreads)
        
        ; Restore internal states
        _ (doseq [bt fresh-bthreads]
            (when-let [saved-state (get-in captured-state 
                                          [:bthread-states (b/name bt)])]
              (b/deserialize bt saved-state)))
        
        ; Reconstruct bprogram state using SAVED bids
        ; ... build event maps from saved bids ...]))
```

1. Call `make-bthreads` to create fresh instances
2. Deserialize saved state into each bthread
3. Use the SAVED bids (not regenerated) to rebuild event maps
4. Calculate next-event from the saved bid data

### Integration with Model Checker

The model checker can use this approach to:
1. Capture state at decision points
2. Explore one path
3. Backtrack by restoring a previous state
4. Explore alternative paths

```clojure
(defn explore-path [state path-so-far make-bthreads]
  (if (seen? state)
    nil ; Cycle detected
    (let [checkpoint (capture-model-checker-state state bthreads)]
      ; Explore each possible next event
      (for [event (possible-events state)]
        (let [next-state (step state event)
              ; ... check properties ...]
          (if violation?
            {:violation true :path (conj path-so-far event)}
            ; Restore and continue exploration
            (let [restored (restore-model-checker-state checkpoint make-bthreads)]
              (explore-path restored (conj path-so-far event) make-bthreads))))))))
```

## Implementation Steps

### Phase 1: Fix Prerequisites
1. **Fix bids serialization bug**: Update `serialize` to return `@xs'` instead of `xs`
2. **Add tests**: Verify serialization/deserialization for all bthread types

### Phase 2: Implement State Management
1. Create `capture-model-checker-state` function
2. Create `restore-model-checker-state` function
3. Add helper functions for normalizing/denormalizing bthread references

### Phase 3: Integrate with Model Checker
1. Modify `tech.thomascothran.pavlov.check/run` to use state capture/restore
2. Implement cycle detection using state hashing
3. Add backtracking logic for path exploration

### Phase 4: Optimize
1. Implement state compression/hashing for cycle detection
2. Add memoization for previously explored states
3. Implement parallel exploration of different paths

## Advantages of This Approach

1. **No Additional Registry**: Uses existing `make-bthreads` as factory
2. **Preserves Semantics**: Works with current bthread/bprogram abstractions
3. **Avoids Non-determinism**: Uses saved bids instead of regenerating
4. **Minimal Changes**: Integrates cleanly with existing code

## Remaining Considerations

1. **Dynamic BThreads**: Handle bthreads created during execution
2. **State Equality**: Define efficient state comparison for cycle detection
3. **Memory Usage**: Consider state compression for large programs
4. **Partial Order Reduction**: Future optimization to reduce state space

## Conclusion

This approach provides a solid foundation for implementing an efficient model checker for Pavlov. By leveraging the existing `make-bthreads` factory pattern and the saved bid information in the bprogram state, we can implement state capture and restoration without major architectural changes. The main prerequisite is fixing the bids serialization bug, after which the implementation is straightforward.
