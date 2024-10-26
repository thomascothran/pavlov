(ns tech.thomascothran.pavlov.bprogram.internal
  (:refer-clojure :exclude [run!])
  (:require [tech.thomascothran.pavlov.bprogram.proto :as bprogram]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.bprogram.internal.state
             :as state])
  (:import [java.util.concurrent LinkedBlockingQueue]))

;; move this elsewhere
(extend-protocol bprogram/BProgramQueue
  LinkedBlockingQueue
  (conj [this event]
    (.put this event))
  (pop [this] (.take this)))

(defn- handle-event!
  [program event]
  (let [!state (get program :!state)
        logger (get program :logger)
        state @!state
        next-state (reset! !state (state/step state event))
        next-event (get next-state :next-event)
        out-queue (get program :out-queue)
        terminate? (event/terminal? next-event)
        recur?    (and next-event (not terminate?))]

    (when logger
      (logger [::handle-event!
               {:event event
                :next-event next-event
                :state state
                :bids (set (vals (:bthread->bid state)))
                :next-bids (set (vals (:bthread->bid next-state)))
                :next-state next-state}]))

    (when event
      (bprogram/conj out-queue event))
    (if recur?
      (recur program next-event)
      (when terminate?
        (deliver (:stopped program) true)
        (bprogram/conj out-queue next-event)))))

(defn- stop!
  [program]
  (bprogram/submit-event! program
                          {:type :pavlov/terminate
                           :terminal true})
  (get program :stopped))

(defn- next-event
  [program]
  (some-> program
          (get :!state)
          deref
          (get :next-event)))

(defn- run!
  [program]
  (let [killed (:killed program)
        in-queue (:in-queue program)
        stopped (:stopped program)]
    (loop [next-event' (next-event program)]
      (if (event/terminal? next-event')
        (deliver stopped true)
        (when-not (realized? killed)
          (when next-event'
            (handle-event! program next-event'))
          (recur (bprogram/pop in-queue)))))))

(defn kill!
  [{:keys [killed stopped]}]
  (deliver killed true)
  (deliver stopped true)
  killed)

(defn make-program!
  ([bthreads]
   (let [in-queue (LinkedBlockingQueue.)
         out-queue (LinkedBlockingQueue.)]
     (make-program! bthreads in-queue out-queue nil)))
  ([bthreads in-queue out-queue opts]
   (let [!state  (atom (state/init bthreads))
         logger  (get opts :logger)

         program
         (with-meta {:!state !state
                     :in-queue in-queue
                     :stopped (promise)
                     :killed (promise)
                     :out-queue out-queue
                     :logger logger}

           {`bprogram/submit-event!
            (fn [_this event]
              (bprogram/conj in-queue event))

            `bprogram/stop! stop!

            `bprogram/kill! kill!

            `bprogram/out-queue (fn [this] (get this :out-queue))})]
     (future (run! program))
     program)))


