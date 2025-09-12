(ns tech.thomascothran.pavlov.model.check
  "Model checking for behavioral programs.

  This namespace provides model checking capabilities for behavioral programs
  by implementing state-space exploration using the StateNavigator protocol.

  The main entry point is the `check` function which explores the state space
  once and detects all violations during traversal."
  (:require [tech.thomascothran.pavlov.search :as search]
            [tech.thomascothran.pavlov.bthread :as bthread]))

;; Internal implementation details below

(defn- assemble-all-bthreads
  "Assembles all bthreads with proper priority ordering."
  [config]
  (let [;; Create bthreads from each category
        safety-bthreads (get config :safety-bthreads)
        main-bthreads (get config :bthreads)
        env-bthreads (get config :environment-threads)]
    ;; Order matters: safety -> main -> env -> deadlock
    (reduce into []
            [safety-bthreads
             main-bthreads
             env-bthreads])))

(defn- check-for-violations
  "Check if the current state represents a violation.
  Returns violation map or nil."
  [wrapped config]
  (let [{:keys [path] :bprogram/keys [state]} wrapped
        next-event (:next-event state)
        ;; Check if the next event has invariant-violated flag
        event-data (when next-event
                     (if (keyword? next-event)
                       {:type next-event}
                       next-event))
        invariant-violated? (get event-data :invariant-violated)]

    (cond
      ;; Check for safety violation
      invariant-violated?
      {:type :safety-violation
       :event event-data
       :path path
       :state state}

      ;; Check for deadlock
      (and (:check-deadlock? config)
           (nil? next-event))
      {:type :deadlock
       :path path
       :state state}

      ;; No violation
      :else
      nil)))

(defn check
  "Model check a behavioral program for safety violations.

  Explores the state space once, checking for:
  - Safety violations (events with :invariant-violated true)
  - Deadlocks (if :check-deadlock? is true)
  - Liveness violations/cycles (if :check-liveness? is true)

  Parameters:
  - config: map with keys:
    :bthreads - the bthreads under test
    :safety-bthreads - bthreads that detect violations
    :environment-bthreads - bthreads that generate events
    :check-deadlock? - if true, detect deadlocks (default: false)
  Returns:
  - nil if no violations found
  - {:type :safety-violation :event event :path [events] :state state}
  - {:type :deadlock :path [events] :state state}"
  [config]
  (let [all-bthreads (assemble-all-bthreads config)
        navigator (search/make-navigator all-bthreads)]

    (search/bfs-reduce
     navigator
     (fn [acc wrapped]
       (if-let [violation (check-for-violations wrapped config)]
         (reduced violation)
         acc))
     nil)))
