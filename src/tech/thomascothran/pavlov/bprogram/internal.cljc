(ns tech.thomascothran.pavlov.bprogram.internal
  (:refer-clojure :exclude [run!])
  (:require [tech.thomascothran.pavlov.bprogram.proto :as bprogram]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.event.publisher.defaults :as pub-default]
            [tech.thomascothran.pavlov.event.publisher.proto :as pub]
            [tech.thomascothran.pavlov.bprogram.internal.state
             :as state])
  #?(:clj (:import [java.util.concurrent LinkedBlockingQueue])))

;; move this elsewhere
#?(:clj (extend-protocol bprogram/BProgramQueue
          LinkedBlockingQueue
          (conj [this event]
            (.put this event))
          (pop [this] (.take this))))

(defn- set-stopped!
  [program]
  #?(:clj (deliver (get program :stopped) true)
     :cljs (throw (ex-info "not implemented" program))))

(defn- set-killed!
  [program]
  #?(:clj (deliver (get program :killed) true)
     :cljs (throw (ex-info "not implemented" program))))

(defn- handle-event!
  [program-opts event]
  (let [!state (get program-opts :!state)
        publisher (get program-opts :publisher)
        state @!state
        next-state (reset! !state (state/step state event))
        next-event (get next-state :next-event)
        terminate? (event/terminal? event)
        recur?    (and next-event (not terminate?))]

    (when event
      (pub/notify! publisher event (get state :bthread->bid)))

    (if recur?
      (recur program-opts next-event)
      (when terminate?
        (set-stopped! program-opts)))))

#?(:clj (defn- run-event-loop!
          [program-opts]
          (let [killed (get program-opts :killed)
                in-queue (get program-opts :in-queue)]
            (loop [next-event' (some-> program-opts
                                       (get :!state)
                                       deref
                                       (get :next-event))]
              (when-not (realized? killed)
                (when next-event'
                  (handle-event! program-opts next-event'))
                (when-not (event/terminal? next-event')
                  (recur (bprogram/pop in-queue))))))))

(defn kill!
  [program-opts]
  (set-killed! program-opts)
  (set-stopped! program-opts)
  (get program-opts :killed))

(defn- subscribe!
  [program k subscriber]
  (let [publisher (get program :publisher)]
    (pub/subscribe! publisher k subscriber)))

#?(:clj
   (defn- submit-event!
     [opts event]
     (let [in-queue (get opts :in-queue)]
       (bprogram/conj in-queue event)))

   :cljs
   (defn- submit-event!
     [opts event]
     (js/setTimeout #(handle-event! opts event) 0)))

(defn- stop!
  [program-opts]
  (submit-event! program-opts
                 {:type :pavlov/terminate
                  :terminal true})
  (get program-opts :stopped))

(defn make-program!
  ([bthreads]
   (make-program! bthreads {}))
  ([bthreads opts]
   (let [!state  (atom (state/init bthreads))
         in-queue (get opts :in-queue #?(:clj (LinkedBlockingQueue.)))
         subscribers (get opts :subscribers {})
         publisher (get opts :publisher
                        (pub-default/make-publisher! {:subscribers subscribers}))

         program-opts
         {:!state !state
          :in-queue in-queue
          :stopped #?(:clj (promise)
                      :cljs (js/Promise.))
          :killed #?(:clj (promise)
                     :cljs (js/Promise.))
          :publisher publisher}

         program (reify bprogram/BProgram
                   (stop! [_] (stop! program-opts))
                   (kill! [_] (kill! program-opts))
                   (subscribe! [_ k f]
                     (pub/subscribe! publisher k f))
                   (submit-event! [_ event]
                     (submit-event! program-opts event)))]

     #?(:clj (future (run-event-loop! program-opts)))
     program)))


