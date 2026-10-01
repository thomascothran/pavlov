(ns tech.thomascothran.pavlov.io.threadpool-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.io.threadpool :as threadpool])
  (:import (java.util.concurrent ExecutorService ThreadPoolExecutor)))

(deftest automatic-executor-test
  (let [^ExecutorService executor (threadpool/make-executor!)
        result (promise)
        virtual? (some #(when (= "isVirtual" (.getName %)) %) (.getMethods Thread))]
    (try
      (.execute executor ^Runnable #(deliver result (Thread/currentThread)))
      (let [worker (deref result 1000 nil)]
        (is (some? worker))
        (when worker
          (is (not (identical? worker (Thread/currentThread))))
          (if virtual?
            (do (is (true? (.invoke virtual? worker (object-array 0))))
                (is (.isDaemon ^Thread worker)))
            (is (instance? ThreadPoolExecutor executor)))))
      (is (threadpool/shutdown! executor 1000))
      (finally (threadpool/shutdown! executor 1000)))))

(deftest unavailable-virtual-threads-fallback-test
  (with-redefs-fn {#'threadpool/make-virtual-executor! (constantly nil)}
    (fn []
      (let [^ExecutorService executor (threadpool/make-executor!)
            result (promise)]
        (try
          (is (instance? ThreadPoolExecutor executor))
          (.execute executor ^Runnable #(deliver result (.isDaemon (Thread/currentThread))))
          (is (= false (deref result 1000 :timeout)))
          (is (threadpool/shutdown! executor 1000))
          (finally (threadpool/shutdown! executor 1000)))))))

(deftest pool-test
  (testing "default worker count scales with available processors"
    (let [^ThreadPoolExecutor pool (threadpool/make-pool!)]
      (try
        (is (= (max 8 (* 2 (.availableProcessors (Runtime/getRuntime))))
               (.getCorePoolSize pool)
               (.getMaximumPoolSize pool)))
        (finally (threadpool/shutdown! pool 1000)))))
  (testing "configured workers execute off-thread and drain on shutdown"
    (let [^ThreadPoolExecutor pool (threadpool/make-pool! {:worker-count 1})
          caller (Thread/currentThread)
          started (promise)
          release (promise)
          completed (promise)]
      (try
        (is (= 1 (.getCorePoolSize pool) (.getMaximumPoolSize pool)))
        (.execute pool ^Runnable
                  (fn []
                    (deliver started (Thread/currentThread))
                    @release))
        (let [worker (deref started 1000 nil)]
          (is (some? worker))
          (when worker
            (is (not (identical? caller worker)))
            (is (false? (.isDaemon ^Thread worker)))))
        ;; Queue work while the sole worker is occupied.
        (.execute pool ^Runnable (fn [] (deliver completed :done)))
        (is (not (realized? completed)))
        (deliver release true)
        (is (true? (threadpool/shutdown! pool 1000)))
        (is (= :done (deref completed 1000 :timeout)))
        (is (.isShutdown pool))
        (finally
          (deliver release true)
          (threadpool/shutdown! pool 1000))))))
