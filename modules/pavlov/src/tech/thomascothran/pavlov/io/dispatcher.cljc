(ns tech.thomascothran.pavlov.io.dispatcher
  "Round-robin dispatch across subscriber-owned, unbounded effect FIFOs.

   Fairness concerns task claiming, not handler start order or execution time.
   Running work is never preempted. Async operations may outlive their tasks."
  (:require #?(:clj [tech.thomascothran.pavlov.io.threadpool :as threadpool]))
  #?(:clj (:import (java.util.concurrent ExecutorService))))

;; Indexed maps avoid platform-specific queue types and retaining consumed tasks.
(def ^:private empty-fifo {:head 0 :tail 0 :items {}})

(defn- fifo-add [queue value]
  (-> queue
      (assoc-in [:items (:tail queue)] value)
      (update :tail inc)))

(defn- fifo-first [queue]
  (get (:items queue) (:head queue)))

(defn- fifo-remove [queue]
  (let [head (inc (:head queue))]
    (if (= head (:tail queue))
      empty-fifo
      (-> queue (assoc :head head) (update :items dissoc (:head queue))))))

(defn- coordinated [dispatcher f]
  #?(:clj (locking (:state dispatcher) (f))
     :default (f)))

(defn- wake-workers! [dispatcher]
  #?(:clj (.notifyAll ^Object (:state dispatcher))
     :default nil))

(defn- claim!
  "Caller holds the coordination lock. Empty subscriber queues are released."
  [{:keys [state]}]
  (let [{:keys [ready queues] :as current} @state]
    (when-let [id (fifo-first ready)]
      (let [queue (get queues id)
            task (fifo-first queue)
            remaining (fifo-remove queue)
            ready (fifo-remove ready)
            more? (some? (fifo-first remaining))]
        (reset! state
                (assoc current
                       :ready (if more? (fifo-add ready id) ready)
                       :queues (if more?
                                 (assoc queues id remaining)
                                 (dissoc queues id))))
        task))))

(defn- report-error! [error]
  #?(:clj (.printStackTrace ^Throwable error)
     :default (js/console.error error)))

(defn- execute-task! [{:keys [on-error!]} task]
  (try
    (task)
    (catch #?(:clj Throwable :default :default) error
      #?(:clj (when (instance? InterruptedException error)
                (.interrupt (Thread/currentThread))))
      (try
        (on-error! error)
        (catch #?(:clj Throwable :default :default) reporting-error
          (report-error! reporting-error))))))

(defn run-next!
  "Claim and run one task without waiting; return true if a task was run.
   Intended for manual dispatchers, which use the same queueing as workers."
  [dispatcher]
  (assert (:manual? dispatcher) "run-next! requires a manual dispatcher")
  (if-let [task (coordinated dispatcher #(claim! dispatcher))]
    (do (execute-task! dispatcher task) true)
    false))

#?(:clj
   (defn- worker! [dispatcher]
     (try
       (loop []
         (when-let [task
                    (coordinated
                     dispatcher
                     (fn []
                       (loop []
                         (if-let [task (claim! dispatcher)]
                           task
                           (when (:accepting? @(:state dispatcher))
                             (.wait ^Object (:state dispatcher))
                             (recur))))))]
           (execute-task! dispatcher task)
           (when-not (.isInterrupted (Thread/currentThread))
             (recur))))
       (catch InterruptedException _
         (.interrupt (Thread/currentThread))))))

(defn- start-workers! [dispatcher]
  #?(:clj
     (when-not (:started? @(:state dispatcher))
       (swap! (:state dispatcher) assoc :started? true)
       (try
         (dotimes [_ (:worker-count dispatcher)]
           (.execute ^ExecutorService (:executor dispatcher)
                     ^Runnable #(worker! dispatcher)))
         (catch Throwable error
           ;; Partially started workers must also be allowed to exit.
           (swap! (:state dispatcher) assoc :accepting? false)
           (wake-workers! dispatcher)
           (throw error))))
     :default nil))

#?(:clj nil
   :default
   (defn- drain! [dispatcher]
     ;; Prevent recursive handler submissions from recursively draining.
     (when-not (:draining? @(:state dispatcher))
       (swap! (:state dispatcher) assoc :draining? true)
       (try
         (loop []
           (when-let [task (claim! dispatcher)]
             (execute-task! dispatcher task)
             (recur)))
         (finally (swap! (:state dispatcher) assoc :draining? false))))))

(defn make-dispatcher!
  "Create a dispatcher. Options:

   :manual?      No automatic execution; use run-next! (default false).
   :on-error!    Receives task exceptions; defaults to printing the error.
   :executor     JVM ExecutorService; defaults to the shared IO pool.
   :worker-count JVM worker loops; defaults to threadpool/default-worker-count.

   Workers start on first enqueue. Each JVM dispatcher reserves worker loops
   on its executor; share one dispatcher across subscribers, not one per
   subscriber. JavaScript drains directly; handlers must return promptly.

   Call stop! before shutting down its executor. An injected executor remains
   caller-owned; stopping a dispatcher never shuts down the shared pool."
  ([] (make-dispatcher! {}))
  ([{:keys [manual? on-error! executor worker-count]
     :or {manual? false on-error! report-error!}}]
   (assert (ifn? on-error!) "on-error! must be callable")
   (let [worker-count (or worker-count #?(:clj (threadpool/default-worker-count)
                                        :default 1))]
     (assert (and (integer? worker-count) (pos? worker-count))
             "worker-count must be a positive integer")
     {:state (atom {:ready empty-fifo :queues {} :next-id 0 :accepting? true})
      :manual? manual?
      :on-error! on-error!
      :worker-count worker-count
      :executor #?(:clj (when-not manual? (or executor (threadpool/pool!)))
                   :default nil)})))

(defn make-queue!
  "Create a subscriber's private FIFO and return its enqueue! function.
   Enqueue and ready-list membership are coordinated atomically. Each nonempty
   FIFO has exactly one ready entry; each claim rotates it to the tail."
  [dispatcher]
  (let [id (coordinated dispatcher
                        #(:next-id (swap! (:state dispatcher) update :next-id inc)))]
    (fn enqueue! [task]
      (assert (ifn? task) "task must be callable")
      (coordinated
       dispatcher
       (fn []
         (when-not (:accepting? @(:state dispatcher))
           (throw (ex-info "Dispatcher is stopped" {})))
         (when-not (:manual? dispatcher)
           (start-workers! dispatcher))
         (swap! (:state dispatcher)
                (fn [{:keys [queues] :as state}]
                  (cond-> (update-in state [:queues id]
                                     #(fifo-add (or % empty-fifo) task))
                    (not (contains? queues id)) (update :ready fifo-add id))))
         (wake-workers! dispatcher)))
      #?(:clj nil
         :default (when-not (:manual? dispatcher) (drain! dispatcher)))
      nil)))

(defn stop!
  "Stop accepting tasks and let workers drain pending effects, then exit.
   Does not wait or shut down the executor. Manual queues remain drainable.
   Application shutdown: stop the dispatcher, then shut down the thread pool
   with a grace period. This does not cancel outstanding async operations."
  [dispatcher]
  (coordinated dispatcher
               #(do (swap! (:state dispatcher) assoc :accepting? false)
                    (wake-workers! dispatcher)))
  nil)

(defonce ^:private shared-dispatcher (delay (make-dispatcher!)))

(defn dispatcher!
  "Return the lazy shared dispatcher. Once stopped, it is not recreated."
  []
  @shared-dispatcher)
