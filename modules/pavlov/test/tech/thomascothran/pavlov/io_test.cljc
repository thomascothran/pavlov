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

(deftest default-dispatch-test
  #?(:clj
     (let [pool (threadpool/make-pool! {:worker-count 1})
           caller (Thread/currentThread)
           worker (promise)
           subscriber (io/make-subscriber!
                       {:go (fn [_] (deliver worker (Thread/currentThread)))})]
       (try
         (with-redefs [threadpool/pool! (constantly pool)]
           (is (nil? (subscriber :go nil))))
         (let [actual (deref worker 1000 nil)]
           (is (some? actual))
           (is (not (identical? caller actual))))
         (finally (threadpool/shutdown! pool 1000))))
     :cljs
     (let [called? (atom false)
           subscriber (io/make-subscriber! {:go (fn [_] (reset! called? true))})]
       (is (nil? (subscriber :go nil)))
       (is (true? @called?)))))
