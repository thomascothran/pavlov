(ns tech.thomascothran.pavlov.io-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [tech.thomascothran.pavlov.bprogram.proto :as bprogram]
            [tech.thomascothran.pavlov.event.defaults]
            [tech.thomascothran.pavlov.io :as io]
            [tech.thomascothran.pavlov.io.dispatcher :as dispatcher]
            #?(:clj [tech.thomascothran.pavlov.io.threadpool :as threadpool])))

(defn- recording-program
  [events]
  (reify bprogram/BProgram
    (submit-event! [_ event] (swap! events conj event))))

(deftest routing-and-completion-test
  (let [dispatcher (dispatcher/make-dispatcher! {:manual? true})
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
                    {:dispatcher dispatcher})]
    (is (nil? (subscriber :unhandled program)))
    (is (false? (dispatcher/run-next! dispatcher)))
    (is (nil? (subscriber triggering-event program)))
    (is (empty? @invocations) "The dispatcher controls when the handler runs")
    (is (true? (dispatcher/run-next! dispatcher)))
    (is (= [triggering-event] @invocations))
    (is (empty? @outcomes) "The handler return value is not an outcome")
    (@complete! {:event :email/sent})
    (is (= [:email/sent] @outcomes) "Completion can occur after invocation")))

(deftest subscribers-have-independent-fair-queues-test
  (let [dispatcher (dispatcher/make-dispatcher! {:manual? true})
        outcomes (atom [])
        a-program (recording-program (atom []))
        b-outcomes (atom [])
        b-program (recording-program b-outcomes)
        handler (fn [{:keys [event on-complete!]}]
                  (swap! outcomes conj (:id event))
                  (on-complete! {:event (:id event)}))
        a (io/make-subscriber! {:go handler} {:dispatcher dispatcher})
        b (io/make-subscriber! {:go handler} {:dispatcher dispatcher})]
    (doseq [id [:a1 :a2 :a3]] (a {:type :go :id id} a-program))
    (doseq [id [:b1 :b2]] (b {:type :go :id id} b-program))
    (dotimes [_ 5] (is (true? (dispatcher/run-next! dispatcher))))
    (is (false? (dispatcher/run-next! dispatcher)))
    (is (= [:a1 :b1 :a2 :b2 :a3] @outcomes))
    (is (= [:b1 :b2] @b-outcomes))))

(deftest default-dispatcher-test
  #?(:clj
     (let [pool (threadpool/make-pool! {:worker-count 1})
           dispatcher (dispatcher/make-dispatcher! {:executor pool :worker-count 1})
           caller (Thread/currentThread)
           worker (promise)]
       (try
         (with-redefs [dispatcher/dispatcher! (constantly dispatcher)]
           (let [subscriber (io/make-subscriber!
                             {:go (fn [_] (deliver worker (Thread/currentThread)))})]
             (is (nil? (subscriber :go nil)))))
         (let [actual (deref worker 1000 nil)]
           (is (some? actual))
           (is (not (identical? caller actual))))
         (finally
           (dispatcher/stop! dispatcher)
           (threadpool/shutdown! pool 1000))))
     :cljs
     (let [called? (atom false)
           subscriber (io/make-subscriber! {:go (fn [_] (reset! called? true))})]
       (is (nil? (subscriber :go nil)))
       (is (true? @called?)))))
