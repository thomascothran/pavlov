# Business scenarios

Model the main business behavior as separate linear scenario bthreads. Give each supported outcome its own bthread instead of branching deeply inside one bthread.

## Rules of thumb

- Start each scenario from the same initiating event when they represent alternate outcomes.
- Keep each scenario linear, even when it reacts to data from prior events.
- End each scenario with a namespaced completion event
- Add policies and invariants in separate bthreads instead of modifying the scenario bthread.

## Example

```clojure
(ns your.domain.scenarios
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn- make-color-foo-happy-path
  []
  (b/bids [{:wait-on #{:workflow/initialize-workflow-a}}
           (fn [{foo-id :foo/id}]
             {:request #{{:type :foo/find
                          :foo/id foo-id}}})
           {:wait-on #{:foo/found}}
           (fn [{foo-id :foo/id}]
             {:request #{{:type :foo/colorize
                          :foo/id foo-id}}})
           {:wait-on #{:foo/colored}}
           {:request #{{:type :your.domain.scenarios/workflow-a-complete}}}]))

(defn- make-foo-not-found
  []
  (b/bids [{:wait-on #{:workflow/initialize-workflow-a}}
           (fn [{foo-id :foo/id}]
             {:request #{{:type :foo/find
                          :foo/id foo-id}}})
           {:wait-on #{:foo/not-found}}
           {:request #{{:type :your.domain.scenarios/workflow-a-aborted
                        :reason :foo-not-found}}}]))

(defn make-bthreads
  []
  {:your.domain.scenarios/happy-path    (make-color-foo-happy-path)
   :your.domain.scenarios/foo-not-found (make-foo-not-found)})
```

If another outcome is possible, add another scenario bthread rather than branching inside `make-color-foo-happy-path`.
