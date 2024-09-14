(ns tech.thomascothran.pavlov.bprogram.defaults
  (:require [tech.thomascothran.pavlov.bprogram.proto :as bprogram]
            [tech.thomascothran.pavlov.bprogram.defaults.internal.state
             :as state])
  #?(:clj (:import [java.util.concurrent LinkedBlockingQueue])))

#?(:clj (extend-protocol bprogram/BProgramQueue
          LinkedBlockingQueue
          (conj [this event] (.offer this event))
          (pop [this] (.take this))))

(defn- -submit-event!
  [this event]        ;; bug here. Use a concurrent queue
  (let [!state (get this :!state)
        state @!state
        handlers (get state :handlers)
        event->bthread (get state :event->bthread)
        result (state/next-state event->bthread event)
        next-event (get result :event)
        next-event->bthread (get result :event->bthread)
        out-queue (get this :out-queue)]

    (swap! !state assoc-in
           [:event->bthread]
           next-event->bthread)
    (doseq [[_id handler] handlers]
      (bprogram/handle handler event))
    (bprogram/conj out-queue event)
    (when next-event (recur this next-event))))

(defn- start!
  [bprogram]
  (println "starting")
  (-submit-event! bprogram {:type :pavlov/init-event}))

(defn- attach-handlers!
  [this handler]
  (swap! (get this :!state)
         update :handlers
         assoc (bprogram/id handler) handler))

(defn make-program
  ([bthreads]
   (let [in-queue #?(:clj (LinkedBlockingQueue.)
                     :cljs [])
         out-queue #?(:clj (LinkedBlockingQueue.)
                      :cljs [])]
     (make-program bthreads in-queue out-queue)))
  ([bthreads in-queue out-queue]
   (let [!state (atom {:event->bthread {:pavlov/init-event (into #{} bthreads)}
                       :bthreads bthreads
                       :handlers  {}})

         stopped #?(:clj (promise)
                    :cljs (js/Promise.))

         program
         (with-meta {:!state !state
                     :in-queue in-queue
                     :out-queue out-queue}

           {`bprogram/attach-handlers! attach-handlers!

            `bprogram/submit-event!
            (fn [_this event]
              (bprogram/conj in-queue event))

            `bprogram/start! start!

            `bprogram/stop!
            (fn [this]
              (-submit-event! this {:type :pavlov/terminate})
              stopped)

            `bprogram/out-queue (fn [this] (get this :out-queue))})]
     #?(:clj (do (future (loop [next-event (pop in-queue)]
                           (if (= :pavlov/terminate (:type next-event))
                             (deliver stopped true)
                             (do
                               (-submit-event! program next-event)
                               (recur (pop in-queue))))))
                 program)
        :cljs program))))


