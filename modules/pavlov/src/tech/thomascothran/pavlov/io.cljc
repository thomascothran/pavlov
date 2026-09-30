(ns tech.thomascothran.pavlov.io
  "Event-driven, one-shot IO handlers."
  (:require [tech.thomascothran.pavlov.bprogram :as bprogram]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.io.dispatcher :as dispatcher]))

(defn make-subscriber!
  "Create a subscriber from a map of event types to handlers.

   Handlers receive {:event event :on-complete! callback}. Call the callback
   with {:event outcome-event} to submit an outcome to the notifying bprogram.
   Handler return values are ignored; the subscriber returns nil.

   Each subscriber has a private FIFO feeding the dispatcher's round-robin
   ready queue. Optional :dispatcher defaults to the shared dispatcher.
   Supply a manual dispatcher for tests. Task errors go to its :on-error!.
   JVM/Babashka workers use the shared pool by default; JavaScript handlers
   run directly and must initiate asynchronous work and return promptly."
  ([handlers] (make-subscriber! handlers {}))
  ([handlers {:keys [dispatcher]}]
   (assert (map? handlers) "handlers must be a map of event types to handlers")
   (let [enqueue! (dispatcher/make-queue! (or dispatcher (dispatcher/dispatcher!)))]
     (fn [triggering-event program]
       (when-let [handler (get handlers (event/type triggering-event))]
         (enqueue!
          (fn []
            (handler {:event triggering-event
                      :on-complete! (fn [{:keys [event]}]
                                      (when (some? event)
                                        (bprogram/submit-event! program event)))}))))
       nil))))
