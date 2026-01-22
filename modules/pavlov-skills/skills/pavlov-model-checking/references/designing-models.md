# Designing Pavlov model-checking models

This guide focuses on model-first development: define the behavior and properties in a small model, check it, then iterate on implementation.

The model checker will take *all* the positive scenarios (what the application should do), *all* your safety and liveness properties, and examine all the execution paths to check the properties of your program.

Generally, there should be one model for your entire feature.

## Terminology

### Safety properties

Safety properties say "something bad never happens." In Pavlov, safety checks are defined as *safety bthreads* and passed under `:safety-bthreads`. They monitor the trace and emit an event with `:invariant-violated true` when the rule is broken.

### Liveness properties

Liveness properties say "something good eventually happens." In Pavlov, liveness checks are expressed alongside the model (conceptually like bthreads that inspect traces) using the `:liveness` map passed to `check/check`.

- **Universal** (`:quantifier :universal`) means the event or predicate must hold on *every* path.
- **Existential** (`:quantifier :existential`) means the event or predicate must hold on *at least one* path.

### Deadlocks

A deadlock means no events are possible and no terminal event occurred. Deadlocks matter when you expect the model to make progress or terminate. If you are modeling an open system, you can add environment bthreads or disable `:check-deadlock?` while refining the model.

### Livelocks

A livelock means the model cycles forever without reaching a terminal event. Livelocks matter when progress should eventually happen; if cycles are intentional, consider `:check-livelock? false` or add a terminal event to represent completion.

## Workflow: model-first with scenarios

### 1) Encode positive scenarios as sequences of events

Positive scenarios describe the different scenarios that the application should support.

Use `b/bids` to define each scenario as a sequence of bids. You can use flat event types or dynamic functions in the sequence.

**Flat event types (keywords):**

```clojure
(defn scenario-flat []
  (b/bids [{:request #{:order/placed}}
           {:request #{:order/paid}}
           {:request #{{:type :order/done}}}
           {:request #{{:type ::order-done-scenario-complete}}}]))
```

Note that you normally will want a namespaced event that only exists in your test suite to indicate that particular scenario is satisfied.

**Dynamic event maps (functions):**

```clojure
(defn order-done-scenario-complete []
  (b/bids [{:request #{{:type :order/placed :order-id 42}}}
           (fn [event]
             (if (:order-accepted event)
               {:request #{{:type :order/paid
                            :order-id (:order-id event)}}}
               {}) ;; will not proceed if the order wasn't accepted
           {:request #{{:type :order/done}}}
           {:request #{{:type ::order-done-scenario-complete}}}]))

(defn positive-scenarios
  []
  {::order-done-scenario-complete (order-done-scenario-complete)
   ;; rest of positive scenarios here
   })
```

The function approach lets you add a bit more logic. However, these should still be linear.

### 2) Turn scenario completion into liveness goals

For each scenario, use the final event name as an **existential** liveness property. This asserts the scenario is *possible at least once*.

```clojure
{:liveness
 {:order-completes
  {:quantifier :existential
   :eventually #{::order-done-scenario-complete}}}}
```

You can also add a **universal** liveness property to ensure every path eventually resolves to a done or cancelled order.

```clojure
{:liveness
 {:order-always-resolves
  {:quantifier :universal
   :eventually #{:order/done :order/cancelled}}}}
```

Add all your liveness properties.

### 3) Add safety bthreads

Once the positive scenarios are modeled, add safety bthreads that emit `:invariant-violated true` for forbidden states (e.g., "no negative payment").

```clojure
(defn no-negative-payment
  []
  (b/on :order/paid
        (fn [{:keys [amount] :as _event}]
           (if (and (number? amount)
                    (neg? amount))
             {:request #{{:type ::negative-payment-violation
                          :amount amount
                          :invariant-violated true}}}
             {}))))
```

`b/on` does not require history. Other bthread constructors may be useful depending on the circumstances. Generally, keep these small and targeted to a single violation.

### 4) Iterate with the model checker

Run `check/check`, inspect violations (see `references/interpret-results.md`), and refine the model and properties before committing to implementation details.
