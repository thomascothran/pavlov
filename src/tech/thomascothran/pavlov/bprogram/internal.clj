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

(defn- run-event-loop!
  [program]
  (let [killed (get program :killed)
        in-queue (get program :in-queue)
        stopped (get program :stopped)]
    (loop [next-event' (next-event program)]
      (if (event/terminal? next-event')
        (deliver stopped true)
        (when-not (realized? killed)
          (when next-event'
            (handle-event! program next-event'))
          (recur (bprogram/pop in-queue)))))))

(defn- run-notify-loop!
  [program]
  (let [killed    (:killed program)
        out-queue (:out-queue program)
        listeners (:listeners program)]
    (loop [event (bprogram/pop out-queue)]
      (when-not (realized? killed)
        (doseq [[k listener] @listeners]
          (try (listener event)
               (catch Throwable _
                 (swap! listeners dissoc k))))
        (if (event/terminal? event)
          (deliver (get program :stopped) true)
          (recur (bprogram/pop out-queue)))))))

(defn- run!
  [program]
  (future (run-notify-loop! program))
  (future (run-event-loop! program)))

(defn kill!
  [{:keys [killed stopped]}]
  (deliver killed true)
  (deliver stopped true)
  killed)

(defn- listen!
  [program k listener]
  (let [listeners (get program :listeners)]
    (swap! listeners assoc k listener)))

(defn make-program!
  ([bthreads]
   (make-program! bthreads {}))
  ([bthreads opts]
   (let [!state  (atom (state/init bthreads))
         logger  (get opts :logger)
         in-queue (get opts :in-queue (LinkedBlockingQueue.))
         out-queue (get opts :out-queue (LinkedBlockingQueue.))
         listeners (get opts :listeners {})

         program
         (with-meta {:!state !state
                     :in-queue in-queue
                     :stopped (promise)
                     :killed (promise)
                     :listeners (atom listeners)
                     :out-queue out-queue
                     :logger logger}

           {`bprogram/submit-event!
            (fn [_this event]
              (bprogram/conj in-queue event))

            `bprogram/stop! stop!

            `bprogram/kill! kill!

            `bprogram/listen! listen!})]

     (run! program)
     program)))


