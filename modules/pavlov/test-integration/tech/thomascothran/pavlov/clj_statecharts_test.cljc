(ns tech.thomascothran.pavlov.clj-statecharts-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [statecharts.core :as fsm]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram.state :as program]
            [tech.thomascothran.pavlov.clj-statecharts :as sc]
            [tech.thomascothran.pavlov.event.proto :as event]))

(def job-chart
  {:id :jobs
   :initial :idle
   :context {:job-id nil}
   :states
   {:idle
    {:on {:job/submitted
          {:target :requesting
           :actions (fsm/assign (fn [state event]
                                  (assoc state :job-id (:job-id event))))}}}
    :requesting
    {::sc/bid (fn [state]
                {:request #{{:type :job/execute :job-id (:job-id state)}}})
     :on {:job/execute :running}}
    :running
    {:on {:job/completed
          {:guard (fn [state event] (= (:job-id state) (:job-id event)))
           :target :idle}}}}})

(deftest requests-follow-selected-events
  (let [worker (sc/bthread job-chart)]
    (is (= #{:job/submitted} (:wait-on (b/notify! worker nil))))
    (is (= #{{:type :job/execute :job-id 42}}
           (:request (b/notify! worker {:type :job/submitted :job-id 42}))))
    (is (= :requesting (:_state (b/state worker))))
    (is (= #{{:type :job/execute :job-id 42}}
           (:request (b/notify! worker nil))) "Inspecting a bid does not advance")
    (is (= {:request #{} :wait-on #{:job/completed} :block #{}}
           (b/notify! worker {:type :job/execute :job-id 42})))
    (b/notify! worker {:type :job/completed :job-id 99})
    (is (= :running (:_state (b/state worker))) "Guard rejects unrelated completion")
    (b/notify! worker {:type :job/completed :job-id 42})
    (is (= :idle (:_state (b/state worker))))))

(deftest scheduler-blocks-request-without-advancing-chart
  (let [worker (sc/bthread job-chart)
        state (program/init
               {:worker worker
                :submit (b/bids [{:request #{{:type :job/submitted :job-id 7}}}])
                :constraint {:block #{:job/execute}}})
        next-state (program/step state {:type :job/submitted :job-id 7})]
    (is (= :requesting (:_state (b/state worker))))
    (is (nil? (program/next-event next-state)))
    (is (= #{{:type :job/execute :job-id 7}}
           (get-in next-state [:bthread->bid :worker :request])))))

(deftest active-ancestors-and-child-transitions
  (let [chart {:id :nested :initial :connected
               ::sc/bid {:block #{:forbidden}}
               :on {:reset :connected}
               :states
               {:connected
                {:initial :ready
                 ::sc/bid {:block #{:disconnect} :wait-on #{:audit}}
                 :on {:stop :offline}
                 :states {:ready {::sc/bid {:request #{:go}}
                                  :on {:go :busy}}
                          :busy {::sc/bid {:request #{:finish}}
                                 :on {:finish :ready}}}}
                :offline {}}}
        worker (sc/bthread chart)]
    (is (= {:request #{:go} :block #{:forbidden :disconnect}
            :wait-on #{:reset :stop :go :audit}}
           (b/notify! worker nil)))
    (is (= #{:finish} (:request (b/notify! worker :go))))
    (is (= #{:finish} (:request (b/notify! worker :audit)))
        "Additional waits may observe an event with no transition")
    (is (= {:request #{} :block #{:forbidden} :wait-on #{:reset}}
           (b/notify! worker :stop)))
    (is (= #{:go} (:request (b/notify! worker :reset))))))

(def parallel-chart
  {:id :parallel :type :parallel
   ::sc/bid {:block #{:unsafe}}
   :regions
   {:left {:initial :a
           ::sc/bid {:wait-on #{:left/observed}}
           :states {:a {::sc/bid {:request #{:left/a}} :on {:advance :b}}
                    :b {::sc/bid {:request #{:left/b}}}}}
    :right {:initial :a
            :states {:a {::sc/bid {:request #{:right/a} :hot true}
                         :on {:advance :b}}
                     :b {::sc/bid {:request #{:right/b}}}}}}})

(deftest parallel-regions-share-an-event
  (doseq [chart [parallel-chart
                {:id :nested-parallel :initial :outer
                 :states {:outer {:initial :work
                                  :states {:work (dissoc parallel-chart :id)}}}}]]
    (let [worker (sc/bthread chart)
          initial (b/notify! worker nil)]
      (is (= #{:left/a :right/a} (:request initial)))
      (is (= #{:unsafe} (:block initial)))
      (is (= #{:advance :left/observed} (:wait-on initial)))
      (is (true? (:hot initial)))
      (let [next-bid (b/notify! worker :advance)]
        (is (= #{:left/b :right/b} (:request next-bid)))
        (is (nil? (:hot next-bid)))))))

(deftest assignments-run-before-eventless-guards-and-bids
  (let [worker
        (sc/bthread
         {:id :assignment :initial :idle :context {:n 0}
          :states
          {:idle {:on {:go {:target :decide
                           :actions (fsm/assign #(update %1 :n + (:amount %2)))}}}
           :decide {::sc/bid {:request #{:must-not-escape}}
                    :always [{:guard (fn [s _] (> (:n s) 1)) :target :ready}
                             {:target :idle}]}
           :ready {::sc/bid (fn [s] {:request #{{:type :ready :n (:n s)}}})}}})]
    (b/notify! worker nil)
    (is (= #{{:type :ready :n 2}}
           (:request (b/notify! worker {:type :go :amount 2}))))
    (is (= :ready (:_state (b/state worker))))))

(deftest restoring-does-not-rerun-entry-actions
  (let [chart {:id :restore :initial :counting :context {:n 0}
               :states {:counting
                        {:entry (fsm/assign (fn [s _] (update s :n inc)))
                         ::sc/bid (fn [s] {:request #{{:type :count :n (:n s)}}})
                         :on {:again :counting}}}}
        original (sc/bthread chart)
        restored (sc/bthread chart)]
    (b/notify! original nil)
    (b/set-state restored (b/state original))
    (is (= (sc/state->bid chart (b/state original))
           (b/notify! restored nil)))
    (is (= 1 (:n (b/state restored))))
    (is (= (b/notify! original :again) (b/notify! restored :again)))
    (is (= 2 (:n (b/state restored))))))

(deftest ordered-requests-are-never-silently-flattened
  (let [chart {:id :ordered :initial :a
               :states {:a {::sc/bid {:request [:first :second]}}}}
        worker (sc/bthread chart)]
    (is (= [:first :second] (:request (b/notify! worker nil))))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"ordered requests cannot be merged"
         (sc/state->bid (assoc chart ::sc/bid {:request #{:other}})
                       {:_state :a})))))

(deftest child-bids-are-combined-without-name-overwrites
  (let [child (b/bids [{:request #{:child}}])
        chart {:id :children :initial :a
               ::sc/bid {:bthreads {:one child}}
               :states {:a {::sc/bid {:bthreads {:two child}}}}}]
    (is (= #{:one :two}
           (set (keys (:bthreads (sc/state->bid chart {:_state :a}))))))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"duplicate child"
         (sc/state->bid (assoc-in chart [:states :a ::sc/bid :bthreads] {:one child})
                       {:_state :a})))))

(deftest custom-events-use-pavlov-event-type
  (let [worker (sc/bthread {:id :custom :initial :a
                            :states {:a {:on {:go :b}}
                                     :b {::sc/bid {:request #{:done}}}}})
        event (reify event/Event
                (type [_] :go)
                (terminal? [_] false))]
    (b/notify! worker nil)
    (is (= #{:done} (:request (b/notify! worker event))))))

(deftest timers-are-rejected-before-execution
  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
       #"Use Pavlov events for timers"
       (sc/bthread {:id :timer :initial :a
                    :states {:a {:after {100 :b}} :b {}}}))))

(deftest state-property-markers-apply-to-active-ancestors-and-regions
  (doseq [marker [::sc/hot ::sc/invariant-violated]
          chart [{:id :root :initial :a marker true :states {:a {}}}
                 {:id :leaf :initial :a :states {:a {marker true}}}
                 {:id :ancestor :initial :a
                  :states {:a {marker true :initial :b :states {:b {}}}}}
                 {:id :parallel :type :parallel
                  :regions {:left {:initial :a :states {:a {}}}
                            :right {marker true :initial :b :states {:b {}}}}}]]
    (let [worker (sc/bthread chart)
          bid (b/notify! worker nil)]
      (if (= marker ::sc/hot)
        (is (true? (:hot bid)))
        (let [violation (first (:request bid))]
          (is (= ::sc/invariant-violated (:type violation)))
          (is (true? (:invariant-violated violation)))
          (is (true? (:terminal violation)))
          (is (seq (::sc/violated-states (:state violation)))))))))

(deftest inactive-and-false-markers-do-not-contribute
  (let [worker (sc/bthread
                {:id :inactive :initial :a
                 :states {:a {::sc/hot false ::sc/invariant-violated false
                              :on {:go :b}}
                          :b {::sc/hot true ::sc/invariant-violated true}}})]
    (is (= {:request #{} :block #{} :wait-on #{:go}} (b/notify! worker nil)))))

(deftest invariant-entry-survives-eventless-transition-and-restoration
  (let [chart {:id :transient :initial :bad :context {:count 0}
               :states {:bad {::sc/invariant-violated true
                              :entry (fsm/assign (fn [s _] (update s :count inc)))
                              :always :done}
                        :done {::sc/bid {:request #{:ordinary-work}}}}}
        original (sc/bthread chart)
        restored (sc/bthread chart)
        violation (b/notify! original nil)]
    (is (= :done (:_state (b/state original))))
    (is (= 1 (:count (b/state original))))
    (is (= #{[:bad]} (::sc/violated-states (b/state original))))
    (is (= #{::sc/invariant-violated} (set (map :type (:request violation)))))
    (b/set-state restored (b/state original))
    (is (= violation (b/notify! restored nil)))))

(deftest markers-require-literal-booleans
  (doseq [marker [::sc/hot ::sc/invariant-violated]]
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"markers must be booleans"
         (sc/bthread {:id :invalid-marker :initial :a
                      :states {:a {marker (constantly false)}}})))))
