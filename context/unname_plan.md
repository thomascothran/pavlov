**Mode: planner**

Let me analyze the feasibility of this API change and create a comprehensive implementation plan.Let me first check the current make-program! implementation more thoroughly:Now let me examine how state/init works with the bthreads:Let me check how bthreads are used throughout to understand all the places that need to change:Let me check how make-program! is called in tests:Let me check if there are other places where bthread names are used:Now I need to understand how assoc-events works since it stores the actual bthread object:Let me check the event selection to understand the full data flow:Now let me create a comprehensive plan:## Comprehensive Implementation Plan: Refactoring make-program! API

### Background and Context

The Pavlov library implements Behavioral Programming (BP) in Clojure. In BP, a program consists of multiple behavioral threads (bthreads) that coordinate through events. Currently, bthreads implement a `name` method that returns their unique identifier within a bprogram.

**Current Issues:**
1. Map bthreads use their entire structure as their name, causing collisions when two maps have identical structure
2. The bthread is responsible for its own registry key, mixing concerns
3. Inconsistent naming across different bthread types (maps vs step vs bids)

**Current API:**
```clojure
(make-program! [bthread1 bthread2 bthread3] {:opts ...})
```

**Proposed API:**
```clojure
(make-program! {:opts ...} :name1 bthread1 :name2 bthread2 :name3 bthread3)
```

### Data Model Changes Required

The current state structure stores:
- `:bthread->bid` - Map from bthread names to their current bids
- `:bthreads-by-priority` - Vector of bthread names in priority order
- `:requests`, `:waits`, `:blocks` - Maps from event types to sets of **bthread objects**

The last point is critical: the state currently stores actual bthread objects in the event maps. With the new design, we need to:
1. Store bthread names instead of objects
2. Maintain a `:name->bthread` registry for lookups

### Detailed Implementation Steps

#### Phase 1: Refactor State Management

**File: `/src/tech/thomascothran/pavlov/bprogram/ephemeral/state.cljc`**

1. **Change state structure** to include name->bthread registry:
   ```clojure
   {:name->bthread {...}      ; NEW: registry of names to bthread objects
    :bthread->bid {...}       ; existing: names to bids
    :bthreads-by-priority [...] ; existing: ordered names
    :requests {...}           ; CHANGE: event-type -> #{names} (not bthreads)
    :waits {...}              ; CHANGE: event-type -> #{names}
    :blocks {...}             ; CHANGE: event-type -> #{names}
    :last-event ...
    :next-event ...}
   ```

2. **Update `init` function**:
   - Accept a map of name->bthread instead of bthread collection
   - Extract priority order from the map (maps preserve insertion order)
   - Build name->bthread registry
   - Store names instead of bthread objects in event maps

3. **Update `assoc-events` function**:
   - Store bthread names instead of bthread objects
   - Change `#(into #{bthread} %)` to `#(into #{bthread-name} %)`

4. **Update `bthreads-to-notify` function**:
   - Return names instead of bthread objects
   - Caller will need to look up actual bthreads from registry

5. **Update `notify-bthreads!` function**:
   - Accept state with name->bthread registry
   - Look up bthreads by name before calling `b/bid`

6. **Update `remove-triggered-bthreads`**:
   - Work with names instead of bthread objects

#### Phase 2: Update make-program! API

**File: `/src/tech/thomascothran/pavlov/bprogram/ephemeral.cljc`**

1. **Change `make-program!` signature**:
   ```clojure
   (defn make-program!
     "Create a behavioral program comprising named bthreads.

      Usage: (make-program! opts :name1 bthread1 :name2 bthread2 ...)

      The order of name-bthread pairs determines priority."
     [opts & name-bthread-pairs]
     (let [pairs (partition 2 name-bthread-pairs)
           _ (assert (even? (count name-bthread-pairs))
                     "Must provide name-bthread pairs")
           name->bthread (into {} pairs)
           initial-state (state/init name->bthread)
           ...]))
   ```

2. **Maintain backward compatibility** (optional):
   - Check if first arg is a map or vector
   - If vector, use old behavior with deprecation warning

#### Phase 3: Update Tests

**Files:**
- `/test/tech/thomascothran/pavlov/bprogram/ephemeral_test.clj`
- `/test/tech/thomascothran/pavlov/bprogram/ephemeral_test.cljs`
- `/test/tech/thomascothran/pavlov/check_test.clj`

Transform all calls from:
```clojure
(make-program! [bthread1 bthread2] {:subscribers {...}})
```

To:
```clojure
(make-program! {:subscribers {...}}
               :bthread1 bthread1
               :bthread2 bthread2)
```

#### Phase 4: Event Selection Updates

**File: `/src/tech/thomascothran/pavlov/event/selection.clj`**

The event selection functions expect bthread->bid maps where keys are bthread names. This should continue to work, but verify all functions handle names correctly.

### Migration Guide for Users

Users will need to update their code:

**Before:**
```clojure
(def my-program
  (make-program!
    [(create-bthread-1)
     (create-bthread-2)
     (create-bthread-3)]
    {:subscribers {...}}))
```

**After:**
```clojure
(def my-program
  (make-program!
    {:subscribers {...}}
    :bthread-1 (create-bthread-1)
    :bthread-2 (create-bthread-2)
    :bthread-3 (create-bthread-3)))
```

### Benefits

1. **Solves collision problem**: Each bthread has an explicit, unique name
2. **Clearer API**: Names are visible at registration time
3. **Better debugging**: Can identify bthreads by meaningful names
4. **Separation of concerns**: Bthreads don't need to manage their own identity
5. **Future flexibility**: Can later remove the `name` method from the protocol

### Potential Challenges

1. **Breaking change**: All existing code using make-program! will need updates
2. **State refactoring**: Significant changes to internal state management
3. **Performance**: Additional lookup overhead (name->bthread)
4. **Testing**: Comprehensive test suite updates needed

### Future Considerations

Once this change is implemented, the `name` method in the BThread protocol becomes unnecessary and could be deprecated/removed in a future version.
