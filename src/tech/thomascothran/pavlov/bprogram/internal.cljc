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

(defn- notify-listeners!
  [program event]
  (let [listeners @(get program :listeners)
        logger   (get program :logger)]
    (doseq [[k listener] listeners]
      (try (listener event)
           (catch #?(:clj Throwable
                     :cljs :default) e
             (when logger
               (logger [::notify-listeners-exception
                        {:exception e}]))
             (swap! listeners dissoc k))))))

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
      #?(:clj  (bprogram/conj out-queue event)
         :cljs (js/setTimeout #(notify-listeners! program event) 0)))
    (if recur?
      (recur program next-event)
      (when terminate?
        #?(:clj  (bprogram/conj out-queue next-event)
           :cljs (js/setTimeout
                  #(notify-listeners! program next-event) 0))))))

(defn- stop!
  [program]
  (bprogram/submit-event! program
                          {:type :pavlov/terminate
                           :terminal true})
  (get program :stopped))

(defn- set-stopped!
  [program]
  #?(:clj (deliver (get program :stopped) true)
     :cljs (throw (ex-info "not implemented" program))))

(defn- set-killed!
  [program]
  #?(:clj (deliver (get program :killed) true)
     :cljs (throw (ex-info "not implemented" program))))

(defn- next-event
  [program]
  (some-> program
          (get :!state)
          deref
          (get :next-event)))

#?(:clj (defn- run-event-loop!
          [program]
          (let [killed (get program :killed)
                in-queue (get program :in-queue)]
            (loop [next-event' (next-event program)]
              (when-not (realized? killed)
                (when next-event'
                  (handle-event! program next-event'))
                (when-not (event/terminal? next-event')
                  (recur (bprogram/pop in-queue))))))))

#?(:clj (defn- run-notify-loop!
          [program]
          (let [killed    (:killed program)
                out-queue (:out-queue program)]
            (loop [event (bprogram/pop out-queue)]
              (when-not (realized? killed)
                (notify-listeners! program event)
                (if (event/terminal? event)
                  (set-stopped! program)
                  (recur (bprogram/pop out-queue))))))))

(defn- run!
  [program]
  #?(:clj (do
            (future (run-notify-loop! program))
            (future (run-event-loop! program)))
     :cljs (throw (ex-info "TODO" program))))

(defn kill!
  [program]
  (set-killed! program)
  (set-stopped! program)
  (get program :killed))

(defn- listen!
  [program k listener]
  (let [listeners (get program :listeners)]
    (swap! listeners assoc k listener)))

(defn- submit-event!
  [program event]
  #?(:clj (let [in-queue (get program :in-queue)]
            (bprogram/conj in-queue event))
     :cljs (handle-event! program event)))

(defn make-program!
  ([bthreads]
   (make-program! bthreads {}))
  ([bthreads opts]
   (let [!state  (atom (state/init bthreads))
         logger  (get opts :logger)
         in-queue (get opts :in-queue #?(:clj (LinkedBlockingQueue.)))
         out-queue (get opts :out-queue #?(:clj (LinkedBlockingQueue.)))
         listeners (get opts :listeners {})

         program
         (with-meta {:!state !state
                     :in-queue in-queue
                     :stopped #?(:clj (promise)
                                 :cljs (js/Promise.))
                     :killed #?(:clj (promise)
                                :cljs (js/Promise.))
                     :listeners (atom listeners)
                     :out-queue out-queue
                     :logger logger}

           {`bprogram/submit-event! submit-event!

            `bprogram/stop! stop!

            `bprogram/kill! kill!

            `bprogram/listen! listen!})]

     (run! program)
     program)))


