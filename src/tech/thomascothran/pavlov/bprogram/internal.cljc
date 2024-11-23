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
  [program event]
  (let [!state (get program :!state)
        state @!state
        next-state (reset! !state (state/step state event))
        next-event (get next-state :next-event)
        terminate? (event/terminal? event)
        recur?    (and next-event (not terminate?))
        publisher (get program :publisher)]

    #_(tap> [::handle-event!
             {:event event
              :terminate? terminate?
              :next-event next-event
              :state state
              :bids (set (vals (:bthread->bid state)))
              :next-bids (set (vals (:bthread->bid next-state)))
              :next-state next-state}])

    (when event
      (pub/notify! publisher
                   (vary-meta event assoc :pavlov/bthread->bids
                              (get state :bthread->bid))))
    (if recur?
      (recur program next-event)
      (when terminate?
        (set-stopped! program)))))

(defn- stop!
  [program]
  (bprogram/submit-event! program
                          {:type :pavlov/terminate
                           :terminal true})
  (get program :stopped))

#?(:clj (defn- run-event-loop!
          [program]
          (let [killed (get program :killed)
                in-queue (get program :in-queue)]
            (loop [next-event' (some-> program
                                       (get :!state)
                                       deref
                                       (get :next-event))]
              (tap> [::next-event' next-event'])
              (when-not (realized? killed)
                (when next-event'
                  (handle-event! program next-event'))
                (when-not (event/terminal? next-event')
                  (recur (bprogram/pop in-queue))))))))

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
     :cljs (js/setTimeout #(handle-event! program event) 0)))

(defn make-program!
  ([bthreads]
   (make-program! bthreads {}))
  ([bthreads opts]
   (let [!state  (atom (state/init bthreads))
         in-queue (get opts :in-queue #?(:clj (LinkedBlockingQueue.)))
         subscribers (get opts :listeners {})
         publisher (get opts :publisher
                        (pub-default/make-publisher! {:subscribers subscribers}))

         program
         (with-meta {:!state !state
                     :in-queue in-queue
                     :stopped #?(:clj (promise)
                                 :cljs (js/Promise.))
                     :killed #?(:clj (promise)
                                :cljs (js/Promise.))
                     :listeners (atom subscribers)
                     :publisher publisher}

           {`bprogram/submit-event! submit-event!

            `bprogram/stop! stop!

            `bprogram/kill! kill!

            `bprogram/listen! listen!})]

     #?(:clj (future (run-event-loop! program)))
     program)))


