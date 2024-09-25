(ns tech.thomascothran.pavlov.bprogram.internal
  (:refer-clojure :exclude [run!])
  (:require [tech.thomascothran.pavlov.bprogram.proto :as bprogram]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.bprogram.internal.state
             :as state])
  #?(:clj (:import [java.util.concurrent LinkedBlockingQueue])))

;; move this elsewhere
#?(:clj (extend-protocol bprogram/BProgramQueue
          LinkedBlockingQueue
          (conj [this event]
            (.put this event))
          (pop [this] (.take this))))

(defn- handle-event!
  [program event]
  (let [!state (get program :!state)
        state @!state
        next-state (reset! !state (state/step state event))
        next-event (get next-state :next-event)
        out-queue (get program :out-queue)]

    (bprogram/conj out-queue event)
    (when next-event
      (recur program next-event))))

(defn- stop!
  [program]
  (bprogram/submit-event! program
                          {:type :pavlov/terminate})
  (get program :stopped))

(defn- next-event
  [program]
  (some-> program
          (get :!state)
          deref
          (get :next-event)))

(defn- run!
  [program]
  (let [in-queue (:in-queue program)
        stopped (:stopped program)]
    (loop [next-event' (next-event program)]
      (if (= :pavlov/terminate (event/type next-event'))
        #?(:clj (deliver stopped true)
           :cljs (.resolve stopped true))
        (do
          (handle-event! program next-event')
          (recur (bprogram/pop in-queue)))))))

(defn make-program!
  ([bthreads]
   (let [in-queue #?(:clj (LinkedBlockingQueue.)
                     :cljs [])
         out-queue #?(:clj (LinkedBlockingQueue.)
                      :cljs [])]
     (make-program! bthreads in-queue out-queue)))
  ([bthreads in-queue out-queue]
   (let [!state (atom (state/init bthreads))

         stopped #?(:clj (promise)
                    :cljs (js/Promise.))

         program
         (with-meta {:!state !state
                     :in-queue in-queue
                     :stopped stopped
                     :out-queue out-queue}

           {`bprogram/submit-event!
            (fn [_this event]
              (bprogram/conj in-queue event))

            `bprogram/stop! stop!

            `bprogram/out-queue (fn [this] (get this :out-queue))})]
     #?(:clj (do (future (run! program))
                 program)
        :cljs (throw (js/Error. "Not implemented"))))))


