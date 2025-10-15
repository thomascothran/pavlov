(ns ^:alpha tech.thomascothran.pavlov.test
  "utilies to make testing easier for pavlov"
  (:require [tech.thomascothran.pavlov.nav :as pnav]))

(defn- find-last-viable-scenario
  [navigable path]
  (or (pnav/follow navigable path)
      (recur navigable (butlast path))))

(defn scenario
  "Check whether `scenario` exists for a group of bthreads

  For example, given a `scenario` of `[:event-a :event-b :event-c]`, check
  whether a corresponding execution path is possible given `bthreads`.

  Arguments
  =========
  - `bthreads`: mapping of bthread-names to bthreads
  - `scenarios`: the event types for a scenario, in sequential order

  Returns
  =======
  A map with the keys:

  `:success`
  ----------
  `true` if the scenario is reachable, else `false`

  `:stuck-at`
  -----------
  If unsuccessful, the last event that was reachable for that scenario.

  For example, if, at the execution path `[:event-a :event-b]`, `:event-c`
  is not reachable, then `:stuck-at` will be `event-b`

  `:available-branches`
  ---------------------
  The events which are available from the point where the check could not
  proceed further with the scenario.

  For example, if at `[:event-a :event-b]`, `:event-c` is not available
  but `event-d` and `event-e` are, then the execution branches for `:event-d`
  and `:event-e` are the value for `:available-branches`

  Example
  -------
  "
  [bthreads scenario]
  (let [bthread-nav  (pnav/root bthreads)
        success-path (pnav/follow bthread-nav scenario)]
    (if success-path
      {:success true}
      (let [state (find-last-viable-scenario bthread-nav scenario)]
        {:success false
         :stuck-at (get state :pavlov/event)
         :available-branches (get state :pavlov/branches)}))))
