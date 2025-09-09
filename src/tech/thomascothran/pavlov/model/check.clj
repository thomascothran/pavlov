(ns tech.thomascothran.pavlov.model.check
  "Model checking for behavioral programs.

  This namespace provides model checking capabilities for behavioral programs
  by implementing state-space exploration using the StateNavigator protocol.

  The main entry point is the `check` function which explores the state space
  once and detects all violations during traversal."
  (:require [tech.thomascothran.pavlov.search :as search]
            [tech.thomascothran.pavlov.bthread :as bthread]
            [tech.thomascothran.pavlov.event.selection :as selection]
            [tech.thomascothran.pavlov.bprogram.state :as state]))

;; Internal implementation details below

(defn- make-deadlock-bthread
  "Creates a bthread that requests a deadlock event."
  []
  (bthread/bids [{:request #{::deadlock}
                  :terminal true
                  :invariant-violated true}]))

(defn- assemble-all-bthreads
  "Assembles all bthreads with proper priority ordering."
  [config]
  (let [;; Create bthreads from each category
        safety-bthreads (get config :safety-bthreads)
        main-bthreads (get config :bthreads)
        env-bthreads (get config :environment-threads)
        deadlock-bthreads (when (:check-deadlock? config)
                            [[::deadlock-bthread (make-deadlock-bthread)]])]
    ;; Order matters: safety -> main -> env -> deadlock
    (reduce into []
            [safety-bthreads
             main-bthreads
             env-bthreads
             deadlock-bthreads])))

(defn- save-bthread-states
  "Save the current state of all bthreads."
  [bp-state]
  (let [name->bthread (:name->bthread bp-state)]
    (into {}
          (map (fn [[name bthread]]
                 (let [bt-state (bthread/state bthread)]
                   [name (if (instance? clojure.lang.IDeref bt-state)
                           @bt-state
                           bt-state)])))
          name->bthread)))

(defn- restore-bthread-states
  "Restore bthread states from a saved snapshot."
  [bp-state saved-states]
  (let [name->bthread (:name->bthread bp-state)]
    (doseq [[name bthread] name->bthread]
      (when-let [saved-state (get saved-states name)]
        (bthread/set-state bthread saved-state)))
    bp-state))

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
           (= next-event ::deadlock))
      {:type :deadlock
       :path path
       :state state}

      ;; No violation
      :else
      nil)))

(defn- make-navigator
  "Create a StateNavigator for the behavioral program."
  [config all-bthreads]
  ;; Initialize the state first, which advances bthreads
  (let [initial-state (state/init all-bthreads)
        ;; Save bthread states AFTER init has advanced them
        saved-initial-states (save-bthread-states initial-state)]
    (reify search/StateNavigator
      (root [_]
        ;; Wrap state with path tracking and saved bthread states
        {:bprogram/state initial-state
         :path []
         :saved-bthread-states saved-initial-states})

      (succ [_ wrapped]
        (let [{:keys [path saved-bthread-states] :bprogram/keys [state]} wrapped
              ;; Get branches from current state (not restored)
              bthread->bid (get state :bthread->bid)
              bthreads-by-priority (get state :bthreads-by-priority)
              branches (selection/prioritized-events bthreads-by-priority
                                                     bthread->bid)]
          ;; Return a sequence of successor states, one for each branch
          (into []
                (map (fn [event]
                       ;; Restore bthread states before stepping
                       (restore-bthread-states state saved-bthread-states)
                       (let [next-state (state/step state event)]
                         {:state {:bprogram/state next-state
                                  :path (conj path event)
                                  :saved-bthread-states (save-bthread-states next-state)}
                          :event event})))
                branches)))

      (identifier [_ wrapped]
        ;; Use saved states instead of live bthread states to avoid mutation issues
        (let [saved-states (:saved-bthread-states wrapped)]
          ;; Create identifier from saved bthread states
          (hash saved-states))))))

(defn check
  "Model check a behavioral program for safety violations.

  Explores the state space once, checking for:
  - Safety violations (events with :invariant-violated true)
  - Deadlocks (if :check-deadlock? is true)
  - Liveness violations/cycles (if :check-liveness? is true)

  Parameters:
  - config: map with keys:
    :bthreads - the bthreads under test (map of name -> constructor fn)
    :safety-bthreads - bthreads that detect violations (map of name -> constructor fn)
    :environment-bthreads - bthreads that generate events (map of name -> constructor fn)
    :check-deadlock? - if true, detect deadlocks (default: false)
  Returns:
  - nil if no violations found
  - {:type :safety-violation :event event :path [events] :state state}
  - {:type :deadlock :path [events] :state state}"
  [config]
  (let [all-bthreads (assemble-all-bthreads config)
        navigator (make-navigator config all-bthreads)]

    (search/bfs-reduce
     navigator
     (fn [acc wrapped]
       (if-let [violation (check-for-violations wrapped config)]
         (reduced violation)
         acc))
     nil)))
