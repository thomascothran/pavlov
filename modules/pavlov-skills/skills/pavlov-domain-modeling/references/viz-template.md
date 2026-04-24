# Visualization template

Use a separate visualization namespace when you want to inspect the reachable state space in Portal or write an HTML graph.

```clojure
(ns test.your.domain.viz
  (:require [portal.api :as portal]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.nav :as pnav]
            [tech.thomascothran.pavlov.viz.cytoscape-html :as ch]
            [tech.thomascothran.pavlov.viz.portal :as pvp]
            [test.your.domain.environment :as environment]
            [your.domain.rules :as rules]
            [your.domain.scenarios :as scenarios]))

(defn all-bthreads []
  (merge (rules/make-bthreads)
         (environment/make-bthreads)
         (scenarios/make-bthreads)))

(defn nav-root []
  (pnav/root (all-bthreads)))

(defn portal-navigable []
  (pvp/bthreads->navigable (all-bthreads)))

(defn open-portal! []
  (let [p (portal/open)]
    (add-tap #'portal/submit)
    (tap> (portal-navigable))
    p))

(defn write-graph! [path]
  (spit path (-> (graph/->lts (all-bthreads))
                 (ch/lts->html))))
```

Keep visualization helpers out of the main modeling namespaces so the model and the inspection tooling can evolve independently.
