(ns tech.thomascothran.pavlov.io-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [tech.thomascothran.pavlov.bprogram.proto :as bprogram]
            [tech.thomascothran.pavlov.event.defaults]
            [tech.thomascothran.pavlov.io :as io]
            #?(:clj [tech.thomascothran.pavlov.io.threadpool :as threadpool])))

(defn- recording-program
  [events]
  (reify bprogram/BProgram
    (submit-event! [_ event] (swap! events conj event))))

(deftest routing-and-completion-test
  (let [tasks (atom [])
        invocations (atom [])
        complete! (atom nil)
        outcomes (atom [])
        program (recording-program outcomes)
        triggering-event {:type :email/send :to "test@example.com"}
        subscriber (io/make-subscriber!
                    {:email/send (fn [input]
                                   (swap! invocations conj (:event input))
                                   (reset! complete! (:on-complete! input))
                                   {:event :ignored-return-value})}
                    {:dispatch! #(swap! tasks conj %)})]
    (is (nil? (subscriber :unhandled program)))
    (is (empty? @tasks))
    (is (nil? (subscriber triggering-event program)))
    (is (= 1 (count @tasks)))
    (is (empty? @invocations) "Dispatch controls when the handler runs")
    ((first @tasks))
    (is (= [triggering-event] @invocations))
    (is (empty? @outcomes) "The handler return value is not an outcome")
    (@complete! {:event :email/sent})
    (is (= [:email/sent] @outcomes) "Completion can occur after invocation")))

(deftest synchronous-completion-test
  (let [outcomes (atom [])
        program (recording-program outcomes)
        subscriber (io/make-subscriber!
                    {:go (fn [{:keys [on-complete!]}]
                           (on-complete! {:event :done}))}
                    {:dispatch! (fn [task] (task))})]
    (is (nil? (subscriber :go program)))
    (is (= [:done] @outcomes))))

(deftest each-effect-is-dispatched-immediately-test
  (let [tasks (atom [])
        outcomes (atom [])
        program (recording-program outcomes)
        subscriber (io/make-subscriber!
                    {:go (fn [{:keys [event on-complete!]}]
                           (on-complete! {:event (:id event)}))}
                    {:dispatch! #(swap! tasks conj %)})]
    (doseq [id [:first :second :third]] (subscriber {:type :go :id id} program))
    (is (= 3 (count @tasks)) "No local queue holds back subsequent effects")
    ;; Execution order belongs to dispatch, not the subscriber.
    (doseq [task (reverse @tasks)] (task))
    (is (= [:third :second :first] @outcomes))))

(deftest custom-dispatch-controls-error-policy-test
  (let [failure (ex-info "handler failed" {})
        errors (atom [])
        subscriber (io/make-subscriber!
                    {:go (fn [_] (throw failure))}
                    {:dispatch! (fn [task]
                                  (try (task)
                                       (catch #?(:clj Throwable :cljs :default) e
                                         (swap! errors conj e))))})]
    (is (nil? (subscriber :go nil)))
    (is (= [failure] @errors)))
  (let [subscriber (io/make-subscriber!
                    {:go (fn [_] nil)}
                    {:dispatch! (fn [_] (throw (ex-info "rejected" {})))})]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (subscriber :go nil)))))

(deftest default-dispatch-test
  #?(:clj
     (let [pool (threadpool/make-pool! {:worker-count 2})
           caller (Thread/currentThread)
           workers [(promise) (promise)]
           release (promise)
           subscriber (io/make-subscriber!
                       {:go (fn [{:keys [event]}]
                              (deliver (nth workers (:id event)) (Thread/currentThread))
                              @release)})]
       (try
         (with-redefs [threadpool/pool! (constantly pool)]
           (dotimes [id 2]
             (is (nil? (subscriber {:type :go :id id} nil)))))
         ;; Both handlers must start before either is released.
         (let [actual (mapv #(deref % 1000 nil) workers)]
           (doseq [worker actual]
             (is (some? worker))
             (is (not (identical? caller worker))))
           (is (not (identical? (first actual) (second actual)))))
         (finally
           (deliver release true)
           (threadpool/shutdown! pool 1000))))
     :cljs
     (let [called? (atom false)
           subscriber (io/make-subscriber! {:go (fn [_] (reset! called? true))})]
       (is (nil? (subscriber :go nil)))
       (is (true? @called?)))))
