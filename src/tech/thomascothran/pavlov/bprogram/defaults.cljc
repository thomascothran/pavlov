(ns tech.thomascothran.pavlov.bprogram.defaults
  (:require [tech.thomascothran.pavlov.bprogram.proto :as bprogram]
            [tech.thomascothran.pavlov.bprogram.defaults.internal.state
             :as state]))

(defn make-program
  [bthreads]
  (let [!state (atom {:event->handlers {}
                      :bthread-queue []
                      :handlers  {}
                      :bthreads bthreads})]
    (with-meta {:!state !state}
      {`bprogram/attach-handlers!
       (fn [_ handler]
         (swap! !state update :handlers
                assoc (bprogram/id handler) handler))

       `bprogram/submit-event! ;; watch out -- concurrency
       (fn [this event]        ;; bug here. Use a concurrent queue
         (let [state @!state
               handlers (get state :handlers)
               event->handlers (get state :event->handlers)
               result (state/next-state event->handlers event)
               next-event (get result :event)
               next-event->handlers (get result :event->handlers)]
           (swap! !state assoc-in
                  [:!state :event->handlers]
                  next-event->handlers)
           (doseq [[_id handler] handlers]
             (bprogram/handle handler event))
           (when next-event (recur this next-event))))

       `bprogram/start!
       (fn start! [this]
         (swap! !state
                (fn [state]
                  (let [bthreads (get state :bthreads)]
                    (-> state
                        (merge (state/next-state bthreads))))))
         (bprogram/submit-event! this {:type :pavlov/init-event}))})))

