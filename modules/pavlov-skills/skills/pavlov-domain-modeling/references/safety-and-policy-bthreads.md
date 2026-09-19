# Safety and policy bthreads

Use safety bthreads to state invariants and policy bthreads to add blocking or compensating behavior without rewriting the main scenarios.

## Safety bthreads

Safety bthreads monitor the trace and request an event with `:invariant-violated true` when a forbidden condition appears.

```clojure
(ns your.domain.safety
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]))

(defn- make-no-green-foos
  []
  (b/scenario
   [(fn [{:keys [event]}]
      {:bid (cond-> {:wait-on #{:foo/found}}
              (and (= :foo/found (e/type event))
                   (= :green (:foo/color event)))
              (assoc :request #{{:type :your.domain.safety/foo-too-ripe
                                 :invariant-violated true}}))
       :next-step :current})]))

(defn make-bthreads
  []
  {:your.domain.safety/no-green-foos (make-no-green-foos)})
```

## Policy bthreads

Policy bthreads let you constrain scenarios additively. They are useful when one business rule should block or redirect behavior without changing the original scenario bthread.

```clojure
(ns your.domain.rules
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]))

(defn- make-dont-color-orange-foos
  []
  (b/scenario
   [(fn [{:keys [event]}]
      {:bid (cond-> {:wait-on #{:foo/found}}
              (and (= :foo/found (e/type event))
                   (= :orange (:foo/color event)))
              (assoc :request #{{:type :workflow/workflow-a-aborted
                                 :reason :tried-to-color-orange-foo}}
                     :block #{:foo/colorize}))
       :next-step :current})]))

(defn make-bthreads
  []
  {:your.domain.rules/dont-color-orange-foos (make-dont-color-orange-foos)})
```

Both examples keep waiting for `:foo/found` and use `:next-step :current` to handle each notification with the same function. The event-type check prevents their own requested events from triggering the rule again.

The policy example blocks `:foo/colorize` without modifying the scenario namespace. That is the core benefit: additive constraints with low co-change.
