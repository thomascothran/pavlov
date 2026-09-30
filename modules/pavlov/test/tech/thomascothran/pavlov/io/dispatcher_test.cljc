(ns tech.thomascothran.pavlov.io.dispatcher-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [tech.thomascothran.pavlov.io.dispatcher :as dispatcher]
            #?(:clj [tech.thomascothran.pavlov.io.threadpool :as threadpool])))

(deftest queue-reentry-and-errors-test
  (let [errors (atom [])
        dispatcher (dispatcher/make-dispatcher! {:manual? true
                                               :on-error! #(swap! errors conj %)})
        a! (dispatcher/make-queue! dispatcher)
        b! (dispatcher/make-queue! dispatcher)
        seen (atom [])
        error (ex-info "handler failed" {})]
    (a! #(do (swap! seen conj :a1)
             (a! (fn [] (swap! seen conj :a2)))))
    (b! #(throw error))
    (b! #(swap! seen conj :b2))
    (dotimes [_ 4] (is (dispatcher/run-next! dispatcher)))
    (is (= [:a1 :a2 :b2] @seen))
    (is (= [error] @errors))
    (is (false? (dispatcher/run-next! dispatcher)))
    ;; A drained queue can become ready again without duplicate entries.
    (a! #(swap! seen conj :a3))
    (dispatcher/stop! dispatcher)
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (a! (fn [] nil))))
    (is (dispatcher/run-next! dispatcher))
    (is (= [:a1 :a2 :b2 :a3] @seen))
    (is (false? (dispatcher/run-next! dispatcher)))))

#?(:clj
   (deftest worker-fairness-and-graceful-shutdown-test
     (let [pool (threadpool/make-pool! {:worker-count 1})
           errors (atom [])
           dispatcher (dispatcher/make-dispatcher! {:executor pool :worker-count 1
                                                  :on-error! #(swap! errors conj %)})
           a! (dispatcher/make-queue! dispatcher)
           b! (dispatcher/make-queue! dispatcher)
           started (promise)
           release (promise)
           seen (atom [])]
       (try
         (a! #(do (deliver started true) @release))
         (is (= true (deref started 1000 :timeout)))
         (doseq [id [:a1 :a2 :a3]] (a! #(swap! seen conj id)))
         (doseq [id [:b1 :b2]] (b! #(swap! seen conj id)))
         (dispatcher/stop! dispatcher)
         (is (false? (.isShutdown pool)) "The dispatcher does not own the executor")
         (deliver release true)
         (is (threadpool/shutdown! pool 1000))
         (is (= [:a1 :b1 :a2 :b2 :a3] @seen))
         (is (empty? @errors))
         (finally
           (deliver release true)
           (dispatcher/stop! dispatcher)
           (threadpool/shutdown! pool 1000))))))

#?(:clj
   (deftest shutdown-interrupts-blocking-handler-test
     (let [pool (threadpool/make-pool! {:worker-count 1})
           interrupted (promise)
           started (promise)
           dispatcher (dispatcher/make-dispatcher! {:executor pool :worker-count 1
                                                  :on-error! #(deliver interrupted %)})
           enqueue! (dispatcher/make-queue! dispatcher)]
       (try
         (enqueue! #(do (deliver started true) (Thread/sleep 60000)))
         (is (= true (deref started 1000 :timeout)))
         (dispatcher/stop! dispatcher)
         (threadpool/shutdown! pool 0)
         (is (instance? InterruptedException (deref interrupted 1000 nil)))
         (is (.awaitTermination pool 1000 java.util.concurrent.TimeUnit/MILLISECONDS))
         (finally
           (dispatcher/stop! dispatcher)
           (threadpool/shutdown! pool 0))))))

#?(:clj
   (deftest concurrent-enqueue-and-claim-test
     (let [pool (threadpool/make-pool! {:worker-count 4})
           errors (atom [])
           dispatcher (dispatcher/make-dispatcher! {:executor pool :worker-count 4
                                                  :on-error! #(swap! errors conj %)})
           queues (vec (repeatedly 3 #(dispatcher/make-queue! dispatcher)))
           seen (atom [])
           producers (doall
                      (for [producer (range 6)]
                        (future
                          (dotimes [n 100]
                            ((nth queues (mod producer 3))
                             #(swap! seen conj [producer n]))))))]
       (try
         (doseq [producer producers] (is (= nil (deref producer 5000 :timeout))))
         (dispatcher/stop! dispatcher)
         (is (threadpool/shutdown! pool 5000))
         (is (= 600 (count @seen) (count (set @seen))))
         (is (empty? @errors))
         (finally
           (dispatcher/stop! dispatcher)
           (threadpool/shutdown! pool 1000))))))
