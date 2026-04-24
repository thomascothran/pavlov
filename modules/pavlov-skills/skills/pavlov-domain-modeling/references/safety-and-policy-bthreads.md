# Safety and policy bthreads

Use safety bthreads to state invariants and policy bthreads to add blocking or compensating behavior without rewriting the main scenarios.

## Safety bthreads

Safety bthreads monitor the trace and request an event with `:invariant-violated true` when a forbidden condition appears.

```clojure
(ns your.domain.safety
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn- make-no-green-foos
  []
  (b/on :foo/found
        (fn [{foo-color :foo/color}]
          (if (= :green foo-color)
            {:request #{{:type :your.domain.safety/foo-too-ripe
                         :invariant-violated true}}}
            {}))))

(defn make-bthreads
  []
  {:your.domain.safety/no-green-foos (make-no-green-foos)})
```

## Policy bthreads

Policy bthreads let you constrain scenarios additively. They are useful when one business rule should block or redirect behavior without changing the original scenario bthread.

```clojure
(ns your.domain.rules
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn- make-dont-color-orange-foos
  []
  (b/on :foo/found
        (fn [{foo-color :foo/color}]
          (if (= :orange foo-color)
            {:request #{{:type :workflow/workflow-a-aborted
                         :reason :tried-to-color-orange-foo}}
             :block #{:foo/colorize}}
            {}))))

(defn make-bthreads
  []
  {:your.domain.rules/dont-color-orange-foos (make-dont-color-orange-foos)})
```

The policy example blocks `:foo/colorize` without modifying the scenario namespace. That is the core benefit: additive constraints with low co-change.
