# Pavlov Project Summary

## Overview

Pavlov is a behavioral programming (BP) library for Clojure and ClojureScript that enables event-driven programming through a unique paradigm of behavior threads (bthreads) and behavior programs (bprograms). It allows developers to create highly decoupled application behaviors that can run in parallel while maintaining deterministic execution.

The library is currently in pre-release status and is available on Clojars as `tech.thomascothran/pavlov`.

### Recent API Changes (January 2025)
The BThread API has been simplified to remove explicit naming requirements. Functions like `step`, `bids`, `repeat`, and `round-robin` no longer require name parameters. BThreads are now identified by their registration order in the BProgram.

## Key Concepts

### Behavioral Programming
- Event-driven programming paradigm that strongly decouples application behaviors
- Units of behavior (bthreads) can request, wait on, or block events
- Synchronous coordination of concurrent behaviors without shared state

### Core Components
1. **BThreads**: Pure functions representing units of application behavior
2. **BPrograms**: Coordinators that select events based on bthread bids
3. **Events**: Named occurrences that drive program execution
4. **Bids**: Specifications of what events a bthread wants to request, wait on, or block
5. **Subscribers**: Side-effect handlers invoked when events are selected

## Directory Structure

```
/home/default/dev/pavlov/
├── src/tech/thomascothran/pavlov/      # Main source code
│   ├── bid/                             # Bid-related functionality
│   ├── bprogram/                        # BProgram implementations
│   │   └── ephemeral/                   # Ephemeral (in-memory) bprogram
│   ├── bthread/                         # BThread implementations
│   ├── event/                           # Event handling and publishing
│   │   └── publisher/                   # Event publisher implementations
│   ├── subscribers/                     # Built-in subscribers (e.g., tap)
│   ├── bprogram.cljc                    # BProgram API
│   ├── bthread.cljc                     # BThread API
│   └── event.cljc                       # Event API
├── test/                                # Test suite
├── examples/                            # Example applications
│   ├── form-validation/                 # Form validation example
│   └── pavlov-form-demo/                # Demo application
├── doc/                                 # Documentation
│   └── design/                          # Design documents
├── deps.edn                             # Clojure dependencies
├── shadow-cljs.edn                      # ClojureScript build configuration
└── build.clj                            # Build and deployment scripts
```

## Key Files and Their Purpose

### Core API Files
- `src/tech/thomascothran/pavlov/bthread.cljc`: Main BThread API with functions like `bids`, `step`, `repeat`, `interlace`
- `src/tech/thomascothran/pavlov/bprogram.cljc`: BProgram API with `stop!`, `kill!`, `submit-event!`, `subscribe!`
- `src/tech/thomascothran/pavlov/bprogram/ephemeral.cljc`: Ephemeral bprogram implementation with `make-program!` and `execute!`

### Protocol Definitions
- `src/tech/thomascothran/pavlov/bthread/proto.cljc`: BThread protocol
- `src/tech/thomascothran/pavlov/bprogram/proto.cljc`: BProgram protocol
- `src/tech/thomascothran/pavlov/event/proto.cljc`: Event protocol

### Configuration Files
- `deps.edn`: Minimal dependencies (zero runtime deps), test deps include kaocha, test.check
- `shadow-cljs.edn`: ClojureScript test configuration for Node.js
- `build.clj`: Deployment to Clojars configuration

## Dependencies

### Runtime Dependencies
- **None** - Pavlov has zero runtime dependencies

### Development Dependencies
- `org.clojure/clojurescript`: 1.11.54
- `thheller/shadow-cljs`: 2.28.18
- `nrepl/nrepl`: 1.2.0
- `cider/piggieback`: 0.4.2

### Test Dependencies
- `lambdaisland/kaocha`: 1.91.1392 (test runner)
- `org.clojure/test.check`: 1.1.1 (property-based testing)
- `org.clojure/math.combinatorics`: 0.3.0

### Build Dependencies
- `io.github.clojure/tools.build`: 0.9.6
- `slipset/deps-deploy`: 0.2.0

## Available APIs and Usage Examples

### Creating BThreads

```clojure
(require '[tech.thomascothran.pavlov.bthread :as b])

;; Simple bid sequence
(b/bids [{:request #{:event-a}}
         {:wait-on #{:event-b}}
         {:block #{:event-c}}])

;; Step function for stateful bthreads
(defn counter-step [state event]
  (if (< state 3)
    [(inc state) {:wait-on #{:increment}}]
    nil))

(b/step counter-step)

;; Repeat a bid n times
(b/repeat 10 {:request #{:tick}})

;; Interlace multiple bthreads
(b/interlace [(b/bids [{:request #{:a}}])
              (b/bids [{:request #{:b}}])])
```

### Creating and Running BPrograms

```clojure
(require '[tech.thomascothran.pavlov.bprogram.ephemeral :as bp])

;; Execute and wait for termination
(bp/execute! [bthread1 bthread2]
             {:subscribers {:logger (fn [event bthread->bid]
                                      (println "Event:" event))}})

;; Create a program and interact with it
(let [program (bp/make-program! [bthread1 bthread2])]
  (bp/submit-event! program {:type :user-action})
  (bp/stop! program))
```

### Event Patterns

```clojure
;; Simple event
{:request #{:click}}

;; Event with data
{:type :submit
 :form {:name "John"}}

;; Compound event (e.g., tic-tac-toe move)
{:type [1 1 :x]}

;; Wait and block pattern
{:wait-on #{:unlock}
 :block #{:critical-action}}
```

## Architecture and Component Interaction

### Event Selection Process
1. External events trigger synchronization points
2. All relevant bthreads submit bids
3. BProgram selects highest priority, unblocked event
4. Selected event is broadcast to subscribers
5. Process repeats until no more requested events

### Key Design Principles
- **Pure Functions**: BThreads must be pure functions
- **No Shared State**: BThreads communicate only through events
- **Deterministic**: Given same inputs, execution is reproducible
- **Side Effect Isolation**: All side effects handled by subscribers

### Protocol-Based Design
All major components (BThread, BProgram, Event) are protocols, allowing:
- Custom implementations
- Extension for specific use cases
- Testing with mock implementations

## Implementation Patterns and Conventions

### Naming Conventions
- BThreads are identified by their registration order when added to a BProgram
- Events are typically keywords or maps with `:type` field
- Subscriber names are keywords

### Common Patterns
1. **Request-Wait-Block**: Control flow through event coordination
2. **Terminate Pattern**: Use `{:terminate true}` to end bprogram
3. **Priority-Based Selection**: Higher priority bthreads win conflicts
4. **Event Cancellation**: Block events to cancel pending requests

### Testing Approach
- Unit tests for individual bthreads (pure functions)
- Integration tests using ephemeral bprograms
- Property-based testing with test.check

## Development Workflow

### Running Tests
```bash
# Run all tests
clojure -T:test

# Run ClojureScript tests
npx shadow-cljs compile test
```

### REPL Development
```bash
# Start Clojure REPL with dev dependencies
clojure -A:dev

# Start ClojureScript REPL
npx shadow-cljs cljs-repl test
```

### Building and Deploying
```bash
# Run CI pipeline
clojure -T:build ci

# Deploy to Clojars
clojure -T:build deploy
```

### Working with Examples
```bash
cd examples/pavlov-form-demo
clojure -A:dev
# Start shadow-cljs watch for hot reloading
```

## Extension Points

### Custom BThread Implementation
Implement the BThread protocol:
- `(notify! [this last-event])`: Notify bthread, which will update its internal state and return next bid
- `(state [this])`: Return current state for serialization
- `(set-state [this serialized])`: Restore from serialized state

### Custom BProgram Implementation
Implement the BProgram protocol for:
- Durable execution backends
- Distributed bprograms
- Custom event selection strategies

### Custom Subscribers
Create functions with signature `(fn [event bthread->bid])` for:
- Logging and debugging
- Side effects (database, API calls)
- UI updates
- Metrics collection
- Can return events

## Future Development (Roadmap)

### In Progress
- Squint support for JavaScript compilation
- Durable execution for fault tolerance

### Planned Features
- Live Sequence Chart generation
- Automated model checking
- Bring-your-own parallelization
- Complete example web application

### Design Goals
1. Zero dependencies maintained
2. Swappable implementations supported
3. Platform agnostic (Clojure/ClojureScript/Squint)

## Debugging and Monitoring

### Built-in Tap Subscriber
```clojure
(require '[tech.thomascothran.pavlov.subscribers.tap :as tap])

;; Use tap subscriber for debugging
{:subscribers {:tap tap/tap}}
```

Shows per sync point:
- Selected event
- Blocked events
- Requested events
- Waiting events
- BThread to bid mapping
- Event to bthread mapping

### Understanding Execution
- Every step is deterministic and auditable
- Use subscribers to trace execution flow
- Higher priority bthreads win event conflicts
- Internal events processed before external events

## Related Resources

- [Behavioral Programming Paper (2012)](https://cacm.acm.org/research/behavioral-programming/)
- [BP Web Page](https://www.wisdom.weizmann.ac.il/~bprogram/more.html)
- [Clojars Package](https://clojars.org/tech.thomascothran/pavlov)
- Design documents in `/doc/design/`
