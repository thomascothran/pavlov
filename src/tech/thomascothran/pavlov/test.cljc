(ns ^:alpha tech.thomascothran.pavlov.test
  "utilies to make testing easier for pavlov

  Disinction from model checker and `nav`
  --------------------------------------
  Bthread states are also navigable via `nav` and
  the search functions. However, those will call all
  possible successor bthreads. For bthreads that don't
  do IO, this is fine.

  However, where bthreads have side effects, neither
  the model checker can roll those back.

  `scenario` allowed you to provide a sequence of events
  that you expect to occur, and checks whether they in fact
  occur in that order. It executes the bprogram normally.
  Hence it is suitable for integration tests with side
  effecting bthreads

  "
  (:require [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
            [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.event :as e]))

(defn passes?
  [scenario event]
  (let [passes*
        (if (fn? scenario)
          scenario
          (comp (partial = scenario) e/type))]
    (passes* event)))

(defn scenario
  "Check whether `scenario` exists for a group of bthreads

  For example, given a `scenario` of `[:event-a :event-b :event-c]`, check
  whether a corresponding execution path is occurs given `bthreads`.

  Arguments
  =========
  - `bthreads`: mapping of bthread-names to bthreads
  - `scenarios`: a sequence of either event types
    or predicate functions that take an event

  `scenarios` do not need to list *every* event that
  happens. The scenario is successful so long as
  the events specified by the scenario occur in
  the specified order, even if other events occur
  between or after them.

  Returns
  =======
  A map with the keys:

  `:success`
  ----------
  `true` if the scenario is occurs, else `false`

  `:stuck-at`
  -----------
  If unsuccessful, the last event that was reachable for that scenario.

  For example, if, at the execution path `[:event-a :event-b]`, `:event-c`
  is not reachable, then `:stuck-at` will be `event-b`

  `:bthread->bid`
  -----------------
  A map of the bthread name to its bid at the point
  the execution got stuck, if applicable.

  Example
  -------
  (let [bthreads
        [[:event-a (b/bids [{:request #{:event-a}}])]
         [:event-b (b/bids [{:wait-on #{:event-a}}
                            {:request #{:event-b}}])]
         [:event-c (b/bids [{:wait-on #{:event-b}}
                            {:request #{:event-c}}])]]]
     (ptest/scenario bthreads [:event-a :event-b :event-c]))


  Caveats
  -------
  If your bthreads are non-deterministic, then you
  may get different results on different runs. Use
  the model checker in that case.
  "
  [bthreads scenario & [config]]
  (let [!events (atom [])
        event-logger (fn [event program-state]
                       (swap! !events conj
                              [event
                               (bp/bthread->bids program-state)]))
        config' (assoc config
                       :subscribers {:event-logger event-logger})
        _ @(bpe/execute! bthreads config')]

    (loop [processed-events    []
           remaining-scenarios scenario
           remaining-events    @!events
           last-match          nil]
      (let [current-scenario   (first remaining-scenarios)
            [current-event
             current-state]    (first remaining-events)
            scenario-passes? (passes?
                              current-scenario
                              current-event)
            stuck-at (first last-match)]
        (cond (nil? current-scenario)
              {:success true
               :execution-path (mapv first @!events)}

              (nil? current-event)
              {:success        false
               :execution-path (mapv first @!events)
               :stuck-at     stuck-at
               :bthread->bid (second last-match)}

              :else
              (recur (conj processed-events current-event)
                     (if scenario-passes?
                       (rest remaining-scenarios)
                       remaining-scenarios)
                     (rest remaining-events)
                     (if scenario-passes?
                       [current-event current-state]
                       last-match)))))))
