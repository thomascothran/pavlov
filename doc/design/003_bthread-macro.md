# Bthread Macro

Created: Sept 1, 2025.

## Context

Defining bthreads can be insufficiently expressive.

For example, we can define a step function as:

```clojure
(def my-step-fn
  (fn [prev-state {event-type :type :as event}]
    (case event-type
      ;; Initialize
      nil [{:initialized true} {:wait-on #{:event-a}}]

      :event-a
      [(update prev-state :a-called inc)
       {:request #{{:type :event-c}}}
        :wait-on #{:event-d}}]))
```

This becomes quite repetitive and noisy. It's not immediately clear to the beginner that a `nil` event only occurs on initialization. Other forms often pop up that mean the same thing:


```clojure
(def my-step-fn
  (fn [prev-state event]
    (if (nil? event)
      [{:initialized true} {:request #{{:type :event-a}}
                            :wait-on #{:event-b}}])
      ;; handle :event-a OR :event-b OR event-c OR event-d
      [(update prev-state :called-times inc)
       {:request #{{:type :event-c}}}
        :wait-on #{:event-d}}]))
```

And there are gotchas:


```clojure
(def my-step-fn
  (fn [prev-state {event-type :type :as event}]
    (case event-type
      ;; Initialize
      nil
      [nil {:request #{#{:type :fire-missiles}}}]

      :fire-missiles
      (do (missile-api/fire!)
          [nil {:request #{{:type :missiles-fired}}}]))))
```

This will throw an error, and the reason why is not expressed clearly in the syntax. Unless the `:missiles-fired` event is blocked, this bthread will be notified of that event, and as it is not handled in the case statement, an error will be thrown.

What is needed is a macro that expresses more clearly what the bthread is doing, and provides safeguards.

## Constraints

We want something that will be easy to lint using clj-kondo and other tools.

## Options

### Option A: Mimic Case

We could do something similar to `case`:

```clojure
(def my-bthread
  (b/thread [prev-state event]
    :pavlov/init  ;; required!
    [{:initialized true}
     {:wait-on #{:fire-missiles}}]

    #{:fire-missiles} ;; set of all events to trigger body
    (let [result (missiles-api/fire!)]
      [prev-state {:request #{{:type :missiles-fired
                               :result result}}}])

    ;; optional default, if not provided returns {} - no requests,
    ;; waits or blocks
    [prev-state {:wait-on #{:fire-missiles}}]))
```

One advantage of this is that we can lint `b/thread` as `defn`. In the future if we wanted to add metadata to it (e.g. for a label), we could.

### Option B: Mimic Defrecord

Or we could do something like:

```clojure
(def my-bthread
  (b/thread [prev-state event]
    (init []) ;; required!
      [{:initialized true}
       {:wait-on #{:fire-missiles}}]

    (on [:fire-missiles] ;; set of all events to trigger body
      (let [result (missiles-api/fire!)]
        [prev-state {:request #{{:type :missiles-fired
                                 :result result}}}])

    ;; optional default, if not provided returns {} - no requests,
    ;; waits or blocks
    (default [])
      [prev-state {:wait-on #{:fire-missiles}}]))

```

## Decision

Option A
