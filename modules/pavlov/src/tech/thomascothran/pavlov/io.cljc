(ns tech.thomascothran.pavlov.io
  "Event-driven, one-shot IO handlers."
  (:require [tech.thomascothran.pavlov.bprogram :as bprogram]
            [tech.thomascothran.pavlov.event :as event]
            #?(:clj [tech.thomascothran.pavlov.io.threadpool :as threadpool]))
  #?(:clj (:import (java.util.concurrent ExecutorService))))

(defn- default-dispatch!
  [task]
  #?(:clj (.execute ^ExecutorService (threadpool/pool!) ^Runnable task)
     :default (task)))

(defn make-subscriber!
  "Create a subscriber from a map of event types to handlers.

   Handlers receive {:event event :on-complete! callback}. Call the callback
   with {:event outcome-event} to submit an outcome to the notifying bprogram.
   Handler and dispatch return values are ignored; the subscriber returns nil.

   Optional :dispatch! accepts a zero-argument task and owns execution policy,
   including buffering, blocking, rejection, and error handling. The caller
   retains ownership of any custom execution resources.

   By default, JVM/Babashka tasks go directly to the shared IO pool's unbounded
   queue. There is no subscriber-local queue, fairness, or backpressure policy.
   JavaScript invokes tasks directly; handlers must initiate asynchronous work
   and return promptly. Exceptions are not intercepted by the subscriber."
  ([handlers] (make-subscriber! handlers {}))
  ([handlers {:keys [dispatch!] :or {dispatch! default-dispatch!}}]
   (assert (map? handlers) "handlers must be a map of event types to handlers")
   (assert (ifn? dispatch!) "dispatch! must be callable")
   (fn [triggering-event program]
     (when-let [handler (get handlers (event/type triggering-event))]
       (dispatch!
        (fn []
          (handler {:event triggering-event
                    :on-complete! (fn [{:keys [event]}]
                                    (when (some? event)
                                      (bprogram/submit-event! program event)))}))))
     nil)))
