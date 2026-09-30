(ns tech.thomascothran.pavlov.io.threadpool-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.io.threadpool :as threadpool])
  (:import (java.util.concurrent ThreadPoolExecutor)))

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
