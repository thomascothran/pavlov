(ns tech.thomascothran.pavlov.io.threadpool
  "JVM/Babashka execution resources for IO.

   This namespace supplies workers, not per-program queueing or fairness.
   Constructed pools are caller-owned; the shared pool is application-owned
   and must not be shut down when an individual bprogram stops."
  (:import (java.util.concurrent ExecutorService LinkedBlockingQueue
                                 ThreadFactory ThreadPoolExecutor TimeUnit)))

(defn default-worker-count
  "A modest floor for blocking IO, scaled to the available processors."
  []
  (max 8 (* 2 (.availableProcessors (Runtime/getRuntime)))))

(defn make-pool!
  "Create a fixed-size pool with named, non-daemon workers.

   Options: :worker-count (defaults to default-worker-count).
   The work queue is unbounded: capacity does not cause rejection or caller
   execution. Submission after shutdown still rejects. This is an execution
   primitive, not an admission limit for outstanding IO operations.
   The caller must shut down the returned ExecutorService."
  ([] (make-pool! {}))
  ([{:keys [worker-count] :or {worker-count (default-worker-count)}}]
   (when-not (and (integer? worker-count) (pos? worker-count)
                  (<= worker-count Integer/MAX_VALUE))
     (throw (ex-info "worker-count must be a positive Java integer"
                     {:worker-count worker-count})))
   (let [counter (atom 0)
         factory (reify ThreadFactory
                   (newThread [_ runnable]
                     (doto (Thread. ^Runnable runnable
                                    (str "pavlov-io-" (swap! counter inc)))
                       (.setDaemon false))))]
     (ThreadPoolExecutor. (int worker-count) (int worker-count)
                          0 TimeUnit/MILLISECONDS
                          (LinkedBlockingQueue.) factory))))

(defonce ^:private shared-pool
  (delay (make-pool!)))

(defn pool!
  "Return the lazy, shared default pool. Shutdown is terminal: no recreation."
  []
  @shared-pool)

(defn shutdown!
  "Stop accepting work, await grace-ms, then request interruption if needed.

   With one argument, shuts down the shared pool. With two, shuts down the
   supplied ExecutorService. Returns whether termination has completed;
   interruption is cooperative and does not guarantee underlying IO stops.
   If the waiting thread is interrupted, requests immediate shutdown and
   restores that thread's interrupt status."
  ([grace-ms] (shutdown! (pool!) grace-ms))
  ([^ExecutorService executor grace-ms]
   (when-not (and (integer? grace-ms) (<= 0 grace-ms Long/MAX_VALUE))
     (throw (ex-info "grace-ms must be a nonnegative Java long"
                     {:grace-ms grace-ms})))
   (.shutdown executor)
   (try
     (when-not (.awaitTermination executor (long grace-ms) TimeUnit/MILLISECONDS)
       (.shutdownNow executor))
     (catch InterruptedException _
       (.shutdownNow executor)
       (.interrupt (Thread/currentThread))))
   (.isTerminated executor)))
