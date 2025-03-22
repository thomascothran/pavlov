(ns tech.thomascothran.pavlov.bprogram.ephemeral
  (:refer-clojure :exclude [run!])
  (:require [tech.thomascothran.pavlov.bprogram.proto :as bprogram]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.event.publisher.defaults :as pub-default]
            [tech.thomascothran.pavlov.event.publisher.proto :as pub]
            [tech.thomascothran.pavlov.bprogram.ephemeral.state
             :as state])
  #?(:clj (:import [java.util.concurrent LinkedBlockingQueue])))

;; move this elsewhere
#?(:clj (extend-protocol bprogram/BProgramQueue
          LinkedBlockingQueue
          (conj [this event]
            (.put this event))
          (pop [this] (.take this))))

#?(:cljs
   (defn deliver
     [m v]
     ((get m :resolve) v)))

(defn- set-stopped!
  [program-opts terminal-event]
  (deliver (get program-opts :stopped)
           terminal-event))

(defn- set-killed!
  [program-opts]
  (deliver (get program-opts :killed) true))

#?(:cljs
   (defn- deferred-promise
     []
     (let [resolve (volatile! nil)
           reject (volatile! nil)]
       {:promise
        (js/Promise. (fn [resolve' reject']
                       (vreset! resolve resolve')
                       (vreset! reject reject')))
        :resolve @resolve
        :reject @reject})))

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
        (set-stopped! program-opts event)))))

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
  (set-stopped! program-opts {:type :pavlov/kill
                              :terminal true})
  #?(:clj (get program-opts :killed)
     :cljs (get-in program-opts [:killed :promise])))

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
  #?(:clj (get program-opts :stopped)
     :cljs (get-in program-opts [:stopped :promise])))

(defn make-program!
  "Create a behavioral program comprising bthreads.

  `bthreads` is a collection of bthreads. Their priority
  is determined by the order in which they are supplied.
  ealier bthreads have higher priority.

  Returns the behavioral program."
  ([bthreads]
   (make-program! bthreads {}))
  ([bthreads opts]
   (let [initial-state (state/init bthreads)
         !state  (atom initial-state)
         in-queue (get opts :in-queue #?(:clj (LinkedBlockingQueue.)))
         subscribers (get opts :subscribers {})
         publisher (get opts :publisher
                        (pub-default/make-publisher! {:subscribers subscribers}))

         stopped #?(:clj (promise)
                    :cljs (deferred-promise))

         program-opts
         {:!state !state
          :in-queue in-queue
          :stopped stopped
          :killed #?(:clj (promise)
                     :cljs (deferred-promise))
          :publisher publisher}

         program (reify bprogram/BProgram
                   (stop! [_] (stop! program-opts))
                   (kill! [_] (kill! program-opts))
                   (stopped [_] stopped)
                   (subscribe! [_ k f]
                     (pub/subscribe! publisher k f))
                   (submit-event! [_ event]
                     (submit-event! program-opts event)))]

     #?(:clj (future (run-event-loop! program-opts))
        :cljs (when-let [next-event (get initial-state :next-event)]
                (submit-event! program-opts next-event)))
     program)))

(defn execute!
  "Execute a behavioral program.

  Returns a promise delivered with the value of the
  terminal event."
  ([bthreads] (execute! bthreads nil))
  ([bthreads opts]
   (-> (make-program! bthreads opts)
       (bprogram/stopped))))
