## Bthread Templates

### rules.clj

```clojure
(ns your.domain.rules
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn make-bthreads []
  {::rule-a (b/bids [{:wait-on #{:event-a}}
                     {:request #{{:type :event-b}}}])})
```

### rules.clj

```clojure
(ns your.domain.safety
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn- make-no-green-foos
  []
  (b/on :foo-found
        (fn [{:keys [foo]}]
           (if (= :green (get foo :color))
              {:request #{{:type ::foo-too-ripe
                           :invariant-violated true}}}
              {}))))

(defn make-bthreads
  []
  {::no-green-foos (make-no-green-foos)})
```

### environment.clj

```clojure
(ns your.domain.environment
  (:require [tech.thomascothran.pavlov.bthread :as b]))

;; Include initiating events here when they are environment inputs.
(defn- make-init-bthread
  []
  ;; establish top-level branching in execution path
  (b/bids [{:request #{:request-foo :request-bar}}]))


(defn- make-find-foo
  []
  (b/bids [{:wait-on #{:find-foo}}
           ;; create branches
           {:request #{{:type :foo-found
                        :foo {:id 1 :color :red}}
                       {:type :foo-found
                        :foo {:id 1 :color :purple}}}}]))

(defn make-bthreads []
  {::init-events (make-init-bthread)
   ::find-foo (make-find-foo)})
```

### scenarios.clj

```clojure
(ns your.domain.scenarios
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn make-bthreads []
  {::scenario-a
   (b/bids [{:wait-on #{:find-foo}}
            {:wait-on #{:foo-found}}
            (fn [{:keys [foo] :as _event}]
              (when (= :red (get foo :color))
                {:request #{{:type ::red-foo-scenario-success}}}))])})
;; etc etc
```

### check.clj

```clojure
(ns your.domain.check
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.model.check :as check]
            [your.domain.rules :as rules]
            [your.domain.safety:as safety]
            [your.domain.environment :as environment]
            [your.domain.scenarios :as scenarios]))

(defn make-liveness
  []
  {:red-foo-scenario-success
   {:quantifier :existential
    :eventually #{::scenarios/red-foo-scenario-success}}

   :purple-foo-found-success
   {:quantifier :existential
    :eventually #{::scenarios/purple-foo-scenario-success}}})

(defn make-config [start-events]
  ;; Keep :bthreads as a map for equal-priority branching.
  (let [bthreads (merge (rules/make-bthreads)
                        (scenarios/make-bthreads))]
    {:bthreads bthreads
     :safety-bthreads (safety/make-bthreads)
     :environment-bthreads (environment/make-bthreads)
     :liveness (make-liveness)}))

(defn run-check [start-events]
  (check/check (make-config start-events)))
```

### viz.clj

```clojure
(ns your.domain.viz
  (:require [tech.thomascothran.pavlov.nav :as pnav]
            [tech.thomascothran.pavlov.viz.portal :as pvp]
            [tech.thomascothran.pavlov.viz.cytoscape-html :as ch]
            [tech.thomascothran.pavlov.graph :as graph]
            [portal.api :as portal]
            [your.domain.rules :as rules]
            [your.domain.environment :as environment]
            [your.domain.scenarios :as scenarios]
            [your.domain.check :as check]))

(defn all-bthreads [start-events]
  (merge (rules/make-bthreads)
         (environment/make-bthreads)
         (scenarios/make-bthreads)))

(defn nav-root [start-events]
  (pnav/root (all-bthreads start-events)))

(defn portal-navigable [start-events]
  (pvp/bthreads->navigable (all-bthreads start-events)))

(defn open-portal! [start-events]
  (let [p (portal/open)]
    (add-tap #'portal/submit)
    (tap> (portal-navigable start-events))
    p))

(defn write-graph! [start-events path]
  (spit path (-> (graph/->lts (all-bthreads start-events))
                 (ch/lts->html))))
```
