# check.clj template

Use a single model-check entry point per feature. Merge the relevant bthread groups, pass the events that must be possible events (including all scenario completion events), and pass safety bthreads separately.

```clojure
(ns test.your.domain.check
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.model.check :as check]
            [test.your.domain.environment :as environment]
            [your.domain.rules :as rules]
            [your.domain.safety :as safety]
            [your.domain.liveness :as liveness]
            [your.domain.scenarios :as scenarios]))

(defn- make-init-bthread
  []
  (b/bids [{:request #{{:type :request-foo}      ;; establish branching
                       {:type :request-bar}}}]))

(defn make-config []
  {:bthreads (merge (rules/make-bthreads)
                    (scenarios/make-bthreads)
                    (liveness/make-bthreads)
                    {:test.your.domain.check/init (make-init-bthread)})
   :safety-bthreads (safety/make-bthreads)
   :environment-bthreads (environment/make-bthreads)
   :possible (scenario/get-possible-event-types)})

(defn run-check []
  (check/check (make-config)))
```

If your initiating events already come from environment bthreads, you may not need `make-init-bthread`. Keep only one clear source of top-level branching.
