(ns tech.thomascothran.pavlov.check
  (:require [tech.thomascothran.pavlov.bprogram.ephemeral
             :as bp]
            [tech.thomascothran.pavlov.bthread :as b]))

(defn- make-deadlock-bthread
  []
  {:request [{:type ::deadlock
              :terminal true
              :invariant-violated true}]})

(defn straight-sequence
  [events]
  [events])

(defn- make-all-bthreads
  "Assembles all bthreads with proper naming for make-program!"
  [{:keys [make-bthreads events safety-bthreads check-deadlock]}]
  (let [main-bthreads (make-bthreads)

        input-threads (mapv
                       (fn [event]
                         [[::input-thread event]
                          (b/bids [{:request #{event}}])])
                       events)

        deadlock-threads (when check-deadlock
                           [[[::deadlock-thread]
                             (make-deadlock-bthread)]])]

    (reduce into []
            [safety-bthreads
             main-bthreads
             input-threads
             deadlock-threads])))

(comment
  (straight-sequence [1 2 3]))

(defn run
  "Check a bprogram to see if it violates safety or
  liveness properties.

  Parameters:
  -----------
  - `:safety-bthreads`: these are the bthreads that
    fire when a safety propert
  - `:make-bthreads`: a 0-arity function returning
     the main bthreads that make up the bprogram.
  - `:events`: this is a collection of events. Depending
    on the `:strategy`, all permutations may be tested,
    random generators may be used, or the events may be
    run in order.
  - `check-deadlock`: if true, check for deadlocks
  - `:subscribers`: any extra subscribers to add. Use these
    for logging or stubbing. Map of name to function.
  - `:strategy`: the a function applied to `:events`. It
    must return a sequence of sequences of events.
    For example, `(partial into [])` will check only
    the sequence passed in `:events`. If no `:strategy` is given
    this is the default behavior.
    `clojure.math.combinatorics/permutations` will check all permutations.


  Return
  ------
  return `nil` if no safety property is violated, otherwise
  returns a map of:

  - `:path`: the path of events that led to the violation
  - `:event`: the type of the event identifing the violation"
  [m]
  (let [safety-bthreads (get m :safety-bthreads [])
        make-bthreads (or (get m :make-bthreads)
                          (constantly []))
        check-deadlock (get m :check-deadlock)
        subscribers (get m :subscribers {})
        strategy (get m :strategy straight-sequence)
        events-combos (strategy (get m :events []))]
    (loop [events-combos' events-combos]
      (when-let [events (first events-combos')]
        (let [all-bthreads (make-all-bthreads
                            {:make-bthreads make-bthreads
                             :events events
                             :safety-bthreads safety-bthreads
                             :check-deadlock check-deadlock})

              !a (atom [])

              state-tracker (fn [x _]
                              (swap! !a conj x))

              result
              @(bp/execute! all-bthreads
                            {:subscribers
                             (assoc subscribers
                                    :state-tracker
                                    state-tracker)})]

          (if (get result :invariant-violated)
            {:path @!a
             :event (last @!a)}
            (recur (rest events-combos'))))))))
