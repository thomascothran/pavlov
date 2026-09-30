# Pavlov IO

Pavlov is synchronous and blocking by default. In some cases it is fine to have bthreads perform IO. But many times it is not.

## Problem
The particular problematic scenario we are targeting is one where a side effect takes longer than we want the bprogram's sync step to take.

An obvious case is:

- Pavlov UI receives a browser submit button
- The event is published to the backend, and a success or fail event is sent from the backend to the frontend
- But the UI's bprogram should not wait until the backend event comes back

A less obvious case has to do with time. (See ./007_time.md.) Many applications will want to schedule events to occur later.

## Constraints and Objectives

### 1. Generalizable
The io functionality should generalize to any one-shot outbound effect triggered by bprogram events, optionally triggering an event to be submitted back to the bprogram

### 2. Configurable
We should be able to configure (on the JVM) how many threads, etc

### 3. Handle errors gracefully
IO can often run into errors. We do not want to crash when that occurs. But the user should be able to supply an error handling strategy

### 4. Order
We do want to order the sequence in which effects are initiated - but not the order they resolve in

### 5. Performance
The IO functionality should be performant. What I have in mind here are cases where we for each event we check the event type to see if it is relevant. When adding more events and event handlers, we should have effectively O(1).

### 6. Cross platfom
Must work on the JVM, on CLJS, on squint, and babashka.

## Out of scope

### 1. Full Blown Scheduling functionality
For example, run this side effect every 15 mintes

### 2. Subscriptions and Streams
In that case, we'd *submit* to the bprogram, not handle the subscription in the pavlov subscriber.

## Solution

Let's take an example:

```clojure
(defn send-email!
  [state {:keys [event on-complete!]}]
  (let [result (aws/ses (make-ses-payload state event))]
    (on-complete! {:event {:type :email/sent
                           :result result}})))

(defn send-text!
  [state {:keys [event on-complete!]}]
  (let [result (aws/sns (make-sns-payload state event))]
    (on-complete! {:event {:type :text/sent
                           :result result}})))

(defn make-io-subscriber
  [state]
  (io {:aws/send-text! (partial send-text! state)
       :aws/send-email! (partial send-email! state)}))
```

`io` will build a subscriber that:

1. Looks up the handler by event type.
2. Invokes the handler with a map containing `:event` (the triggering event) and `:on-complete!` (a completion callback), without making the bprogram wait for the external outcome.
3. When the handler calls `on-complete!` with `{:event outcome-event}`, submits the enclosed event to the bprogram.

### Handler contract

```clojure
(fn [{:keys [event on-complete!]}]
  ;; Initiate a one-shot operation, then report its outcome:
  (on-complete! {:event outcome-event}))
```

The handler's return value is not the operation's outcome. Completion may happen during invocation or later through an asynchronous callback. The handler adapts its client's completion mechanism (for example, a JavaScript Promise) to `on-complete!`; the IO facility does not need to interpret that client's asynchronous return type.

Success and failure are both application-defined outcome events. There is no separate `fail!` callback. For example, a handler may complete with `{:event {:type :email/failed :reason :service-unavailable}}`. The IO facility submits the event without interpreting whether it represents success or failure.

The examples above assume blocking clients and therefore require execution outside the synchronous bprogram path, such as on a JVM worker. JavaScript handlers must initiate asynchronous work and return promptly. The shared callback contract does not itself provide execution isolation or guarantee initiation order.

Execution errors remain distinct from application outcome events. The IO facility can catch errors during handler invocation, but cannot automatically catch errors in later asynchronous callbacks or observe Promise rejections that the handler does not expose. Handlers must explicitly bridge asynchronous failures into outcome events or another reporting mechanism; the configurable error policy remains to be designed.

### JVM and Babashka execution

Blocking handlers run concurrently on a library-owned, global `ThreadPoolExecutor`, shared across bprograms, with non-daemon workers. Users may optionally supply their own `ExecutorService` to control execution behavior; they retain shutdown ownership.

The shared executor requires explicit application-level shutdown, not shutdown when an individual bprogram stops: stop accepting tasks, allow a grace period for queued/running tasks to finish, then request interruption with `shutdownNow()`. Interruption is cooperative; shutdown cannot guarantee that underlying IO stops. A JVM shutdown hook alone is insufficient because non-daemon workers can prevent normal shutdown from starting.

The same approach works in Babashka. Construction with a bounded queue, non-daemon workers, task execution, and graceful shutdown were verified on Babashka 1.12.218. An executor supplies queueing but not operation timeouts; async handlers may also outlive their executor tasks.

Need to decide:
- Default pool size, queue capacity, and rejection policy (without blocking the bprogram)
- Shutdown grace period and sequencing with bprogram shutdown
- Precisely what initiation ordering guarantees mean under concurrent execution
- How to report completion without an event
- How to handle duplicate completion calls or an exception after completion
- How the configurable error handling strategy works
- What happens to outstanding operations and late completions when the bprogram stops
- What about cancellation? Maybe impossible?
- Timeouts? Maybe put that on the user that constructs the function
