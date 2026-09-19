(ns bench.pavlov-core
  "Reproducible JVM benchmarks for Pavlov's bthreads and ephemeral scheduler.

  Public workload functions are intentionally retained for REPL exploration.
  Run `validate!` before benchmarking, then `benchmark!` or `profile!`."
  (:require [clj-async-profiler.core :as profiler]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [criterium.bench :as criterium]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as ephemeral]
            [tech.thomascothran.pavlov.bprogram.state :as state]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as event])
  (:import (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)))

(def tick-bid {:request [:tick]})
(def terminal-event {:type :bench/done :terminal true})
(def transition-inputs (long-array (range 10000)))

(defn done-event
  [count]
  (assoc terminal-event :count count))

(defn plain-transitions
  "Straight-Clojure lower bound that folds n values from a mutable input array."
  [^long n]
  (let [^longs inputs transition-inputs]
    (loop [i (long 0)
           value (long 0)]
      (if (< i n)
        (recur (unchecked-inc i)
               (unchecked-add value (aget inputs i)))
        value))))

(defn plain-event-count
  "Count n externally read events; the array read prevents loop elimination."
  [^long n]
  (let [^longs inputs transition-inputs]
    (loop [i (long 0)
           count (long 0)]
      (if (< i n)
        (recur (unchecked-inc i)
               (if (<= 0 (aget inputs i)) (unchecked-inc count) count))
        count))))

(defn plain-terminal-program
  "Straight-Clojure function with the same externally visible terminal result."
  [^long n]
  (done-event (plain-event-count n)))

(defn direct-bthread-transitions
  "Fold n values through a fresh bthread directly, without a bprogram scheduler."
  [^long n]
  (let [^longs inputs transition-inputs
        bt (b/step (fn [previous event]
                     [(unchecked-add (long (or previous 0)) (long event))
                      tick-bid]))]
    (loop [i (long 0)]
      (if (< i n)
        (do (b/notify! bt (aget inputs i))
            (recur (unchecked-inc i)))
        (b/state bt)))))

(defn run-core-state
  "Advance a prebuilt Pavlov state through n internally selected events."
  [initial-state ^long n]
  (loop [i (long 0)
         current initial-state]
    (if (< i n)
      (recur (unchecked-inc i)
             (state/step current (:next-event current)))
      current)))

(defn single-bthread-state
  []
  (state/init [[:driver tick-bid]]))

(defn ephemeral-driver
  [^long n]
  (b/step
   (fn [previous _event]
     (let [i (long (or previous 0))]
       (if (< i n)
         [(unchecked-inc i) tick-bid]
         [i {:request [(done-event i)]}])))))

(defn ephemeral-program
  "Run n :tick events through execute!, including its async shell and shutdown."
  [^long n]
  @(ephemeral/execute! [[:driver (ephemeral-driver n)]]))

(defn synchronous-ephemeral-prototype
  "Caller-thread prototype for internally driven ephemeral programs.

  This deliberately models only execute!'s no-subscriber, no-external-input
  path. It retains initialization, deadlock detection, terminal state stepping,
  priority, and the same driver workload, but omits promises, publisher, atom,
  queue, and future. It is evaluation code, not a public API proposal."
  [^long n]
  (let [deadlock-event {:type ::prototype-deadlock :terminal true}
        initial-state
        (state/init [[:driver (ephemeral-driver n)]
                     [::deadlock {:request [deadlock-event]}]])]
    (loop [current-state initial-state]
      (if-let [next-event (:next-event current-state)]
        (let [next-state (state/step current-state next-event)]
          (if (event/terminal? next-event)
            next-event
            (recur next-state)))
        deadlock-event))))

(defn scheduler-thread-allocation
  "Measure bytes allocated on execute!'s scheduler thread between subscriber
  callbacks after the first :tick and at the terminal event. For n ticks this
  interval contains n scheduler transitions (the remaining ticks plus the
  terminal transition), while excluding program/future startup."
  [^long n]
  (let [^ThreadMXBean bean (ManagementFactory/getThreadMXBean)
        _ (when-not (.isThreadAllocatedMemoryEnabled bean)
            (.setThreadAllocatedMemoryEnabled bean true))
        start-bytes (volatile! nil)
        callback-count (volatile! (long 0))
        allocated (promise)
        subscriber
        (fn [event _program]
          (vswap! callback-count unchecked-inc)
          (let [thread-id (.getId (Thread/currentThread))
                current-bytes (.getThreadAllocatedBytes bean thread-id)]
            (if (nil? @start-bytes)
              (vreset! start-bytes current-bytes)
              (when (:terminal event)
                (deliver allocated (- current-bytes @start-bytes))))))
        driver
        (b/step
         (fn [previous _event]
           (let [i (long (or previous 0))]
             (if (< i n)
               [(unchecked-inc i) tick-bid]
               [i {:request [(done-event i)]}]))))
        result @(ephemeral/execute! [[:driver driver]]
                                    {:subscribers {:allocation subscriber}})]
    {:result result
     :callbacks @callback-count
     :measured-transitions n
     :allocated-bytes @allocated
     :allocated-bytes-per-transition (/ (double @allocated) (double n))}))

(defn parked-state
  "One requester and n-1 bthreads parked on unrelated event types."
  [n]
  (state/init
   (into [[:driver tick-bid]]
         (map (fn [i]
                [[:parked i] {:wait-on #{[:unrelated i]}}]))
         (range 1 n))))

(defn interested-state
  "n bthreads that all request, and are therefore all notified by, :tick."
  [n]
  (state/init
   (mapv (fn [i] [[:interested i] tick-bid]) (range n))))

(defn request-width-state
  "One bid with n ordered requests; :tick remains highest priority."
  [n]
  (state/init
   [[:driver {:request (into [:tick]
                             (map (fn [i] [:request i]))
                             (range 1 n))}]]))

(defn wait-width-state
  "One repeatedly triggered bid with n unrelated waits in addition to :tick."
  [n]
  (state/init
   [[:driver {:request [:tick]
              :wait-on (into #{} (map (fn [i] [:wait i])) (range n))}]]))

(defn block-width-state
  "One :tick requester plus one parked bid blocking n unrelated event types."
  [n]
  (state/init
   [[:driver tick-bid]
    [:blocker {:block (into #{} (map (fn [i] [:blocked i])) (range n))}]]))

(defn construct-bid-script
  "Construct b/bids, which eagerly realizes its input sequence into a vector."
  [^long n]
  (b/bids (repeat n tick-bid)))

(defn run-bid-script
  "Construct a script of script-length bids and consume steps of it directly."
  [^long script-length ^long steps]
  (let [bt (construct-bid-script script-length)]
    (loop [i (long 0)
           bid nil]
      (if (< i steps)
        (recur (unchecked-inc i) (b/notify! bt :tick))
        bid))))

(defn burn
  "Synthetic CPU work used only by the eager blocking comparison."
  [^long iterations ^long seed]
  (loop [i (long 0)
         x seed]
    (if (< i iterations)
      (recur (unchecked-inc i)
             (unchecked-add (unchecked-multiply x (long 1664525))
                            (unchecked-add i (long 1013904223))))
      x)))

(defn plain-guarded
  "Plain implementation that applies the same exclusion before expensive work."
  [^long events _burn-iterations]
  (plain-event-count events))

(defn plain-eager
  "Intentionally eager implementation: does work that is discarded by a block."
  [^long events ^long burn-iterations]
  (loop [i (long 0)
         x (long 0)]
    (if (< i events)
      (recur (unchecked-inc i) (burn burn-iterations (unchecked-add x i)))
      x)))

(defn blocked-work-state
  "Build a program where :expensive is requested but blocked, so worker parks."
  [burn-iterations invocation-counter]
  (let [worker
        (b/step
         (fn [previous event]
           (if (= :expensive event)
             (do (swap! invocation-counter inc)
                 [(burn burn-iterations (long (or previous 0)))
                  {:wait-on #{:expensive}}])
             [(long (or previous 0)) {:wait-on #{:expensive}}])))]
    (state/init
     [[:requester {:request [:expensive :tick]}]
      [:blocker {:block #{:expensive}}]
      [:expensive-worker worker]])))

(defn pavlov-blocked
  [initial-state events]
  (run-core-state initial-state events))

(defn validate!
  "Check semantic assumptions used by every benchmark case."
  []
  (assert (= 4950 (plain-transitions 100)))
  (assert (= 45 (direct-bthread-transitions 10)))
  (assert (= :tick (:next-event (run-core-state (single-bthread-state) 10))))
  (assert (= (done-event 10) (plain-terminal-program 10)))
  (assert (= (done-event 10) (ephemeral-program 10)))
  (assert (= (done-event 10) (synchronous-ephemeral-prototype 10)))
  (doseq [n [1 10 100]]
    (assert (= :tick (:next-event (run-core-state (parked-state n) 3))))
    (assert (= :tick (:next-event (run-core-state (interested-state n) 3)))))
  (doseq [n [1 10 100]]
    (assert (= :tick (:next-event (run-core-state (request-width-state n) 3))))
    (assert (= :tick (:next-event (run-core-state (wait-width-state n) 3))))
    (assert (= :tick (:next-event (run-core-state (block-width-state n) 3)))))
  (assert (= tick-bid (dissoc (run-bid-script 100 10) :unused)))
  (let [calls (atom 0)
        blocked-state (blocked-work-state 100 calls)
        result (pavlov-blocked blocked-state 100)]
    (assert (= :tick (:next-event result)))
    (assert (zero? @calls)))
  :valid)

(defn- benchmark-case
  [group id units f]
  {:group group :id id :units units :f f})

(defn benchmark-cases
  "Create cases and their prebuilt immutable states outside timed regions."
  []
  (let [transition-count 1000
        scheduler-events 100
        full-events 100
        block-events 100
        burn-iterations 1000
        blocked-calls (atom 0)
        blocked-state (blocked-work-state burn-iterations blocked-calls)]
    (concat
     [(benchmark-case :overhead :plain-tight-loop transition-count
                      #(plain-transitions transition-count))
      (benchmark-case :overhead :direct-bthread transition-count
                      #(direct-bthread-transitions transition-count))
      (benchmark-case :overhead :pavlov-state-1 scheduler-events
                      #(run-core-state (single-bthread-state) scheduler-events))
      (benchmark-case :end-to-end :plain-terminal full-events
                      #(plain-terminal-program full-events))
      (benchmark-case :end-to-end :ephemeral-execute full-events
                      #(ephemeral-program full-events))]

     (for [n [1 10 100 1000]
           :let [initial-state (parked-state n)]]
       (benchmark-case :parked-bthreads (keyword (str "n-" n)) scheduler-events
                       #(run-core-state initial-state scheduler-events)))

     (for [n [1 10 100 1000]
           :let [initial-state (interested-state n)]]
       (benchmark-case :interested-bthreads (keyword (str "n-" n)) scheduler-events
                       #(run-core-state initial-state scheduler-events)))

     (for [n [1 10 100 1000]
           :let [initial-state (request-width-state n)]]
       (benchmark-case :request-width (keyword (str "n-" n)) scheduler-events
                       #(run-core-state initial-state scheduler-events)))

     (for [n [0 10 100 1000]
           :let [initial-state (wait-width-state n)]]
       (benchmark-case :wait-width (keyword (str "n-" n)) scheduler-events
                       #(run-core-state initial-state scheduler-events)))

     (for [n [0 10 100 1000]
           :let [initial-state (block-width-state n)]]
       (benchmark-case :block-width (keyword (str "n-" n)) scheduler-events
                       #(run-core-state initial-state scheduler-events)))

     (for [n [1 100 10000]]
       (benchmark-case :script-construction (keyword (str "n-" n)) 1
                       #(construct-bid-script n)))

     (for [n [100 1000 10000]]
       (benchmark-case :script-construct-and-consume (keyword (str "n-" n)) 100
                       #(run-bid-script n 100)))

     [(benchmark-case :blocking :plain-guarded block-events
                      #(plain-guarded block-events burn-iterations))
      (benchmark-case :blocking :plain-eager-burn-1000 block-events
                      #(plain-eager block-events 1000))
      (benchmark-case :blocking :plain-eager-burn-10000 block-events
                      #(plain-eager block-events 10000))
      (benchmark-case :blocking :plain-eager-burn-100000 block-events
                      #(plain-eager block-events 100000))
      (benchmark-case :blocking :pavlov-blocked block-events
                      #(pavlov-blocked blocked-state block-events))
      (benchmark-case :blocking-proof :expensive-worker-invocations 1
                      #(deref blocked-calls))])))

(defn- per-invocation
  [sample-value batch-size]
  (when (number? sample-value)
    (/ (double sample-value) (double batch-size))))

(defn measure-case!
  "Run one case with Criterium and return compact, EDN-safe statistics."
  [{:keys [group id units f]} limit-time-s]
  (println "BENCH" group id)
  (criterium/bench (f)
                   :viewer :print
                   :view [:stats]
                   :metric-ids [:elapsed-time :thread-allocation]
                   :limit-time-s (long (Math/ceil (double limit-time-s))))
  (let [last-bench (criterium/last-bench)
        data (:data last-bench)
        stats (:stats data)
        batch-size (:batch-size stats)
        elapsed (get-in stats [:stats :elapsed-time])
        allocation (get-in stats [:stats :thread-allocation])
        median-ns (per-invocation (:median elapsed) batch-size)
        mean-ns (per-invocation (:mean elapsed) batch-size)
        allocated-bytes (per-invocation (:median allocation) batch-size)]
    {:group group
     :id id
     :logical-units units
     :samples (:n elapsed)
     :batch-size batch-size
     :median-ns-per-invocation median-ns
     :mean-ns-per-invocation mean-ns
     :median-ns-per-logical-unit (/ median-ns units)
     :allocated-bytes-per-invocation allocated-bytes
     :allocated-bytes-per-logical-unit (/ allocated-bytes units)}))

(defn environment
  []
  {:timestamp (str (java.time.Instant/now))
   :java-version (System/getProperty "java.version")
   :java-vm (System/getProperty "java.vm.name")
   :clojure-version (clojure-version)
   :available-processors (.availableProcessors (Runtime/getRuntime))
   :os (str (System/getProperty "os.name") " " (System/getProperty "os.version"))})

(defn- benchmark-cases!
  [cases limit-time-s output-file]
  (validate!)
  (let [results (mapv #(measure-case! % limit-time-s) cases)
        evidence {:environment (environment)
                  :limit-time-s (long (Math/ceil (double limit-time-s)))
                  :results results}]
    (spit output-file (with-out-str (pprint/pprint evidence)))
    evidence))

(defn benchmark!
  "Run the full suite and write compact evidence to dev/bench/results.edn."
  ([] (benchmark! 2))
  ([limit-time-s]
   (benchmark-cases! (benchmark-cases)
                     limit-time-s
                     "dev/bench/results.edn")))

(defn benchmark-execute-baseline!
  "Refresh execute! latency and scheduler-thread allocation evidence."
  ([] (benchmark-execute-baseline! 5))
  ([limit-time-s]
   (let [cases (filter #(= :end-to-end (:group %)) (benchmark-cases))
         latency (benchmark-cases! cases
                                   limit-time-s
                                   "dev/bench/execute-post-lifecycle.edn")
         allocations (mapv (fn [_] (scheduler-thread-allocation 100))
                           (range 20))
         bytes-per-transition (sort (map :allocated-bytes-per-transition
                                         allocations))
         allocation-evidence
         {:environment (environment)
          :method {:ticks 100
                   :samples (count allocations)
                   :boundary "subscriber callback after first tick through terminal callback"
                   :includes "100 scheduler transitions plus subscriber/MXBean observation"
                   :excludes "execute! startup and first selected event"}
          :median-allocated-bytes-per-transition
          (nth bytes-per-transition (quot (count bytes-per-transition) 2))
          :samples allocations}]
     (spit "dev/bench/execute-scheduler-allocation.edn"
           (with-out-str (pprint/pprint allocation-evidence)))
     {:latency latency :scheduler-allocation allocation-evidence})))

(defn benchmark-synchronous-evaluation!
  "Compare execute! with a restricted caller-thread prototype in a fresh JVM."
  ([] (benchmark-synchronous-evaluation! 5))
  ([limit-time-s]
   (let [cases
         (for [n [1 10 100 1000]
               [id f] [[:execute #(ephemeral-program n)]
                       [:caller-thread-prototype
                        #(synchronous-ephemeral-prototype n)]]]
           (benchmark-case :synchronous-evaluation
                           (keyword (str (name id) "-n-" n))
                           n
                           f))]
     (benchmark-cases! cases
                       limit-time-s
                       "dev/bench/synchronous-evaluation.edn"))))

(def regression-groups
  #{:interested-bthreads :request-width :wait-width :block-width})

(defn benchmark-regressions!
  "Benchmark fan-in and bid/index widths without timing assertions.

  Output is named explicitly so each optimization phase retains comparable
  evidence instead of overwriting its predecessor."
  ([output-file] (benchmark-regressions! output-file 2))
  ([output-file limit-time-s]
   (benchmark-cases! (filter #(regression-groups (:group %))
                             (benchmark-cases))
                     limit-time-s
                     output-file)))

(defn read-results
  []
  (edn/read-string (slurp "dev/bench/results.edn")))

(defn- copy-profile!
  [source filename]
  (let [target (io/file "dev/bench/profiles" filename)]
    (.mkdirs (.getParentFile target))
    (io/copy (io/file source) target)
    (.getPath target)))

(defn- profile-workload!
  [options workload]
  (let [result (volatile! nil)]
    (profiler/start options)
    (try
      (workload)
      (finally
        (vreset! result (profiler/stop options))))
    @result))

(defn- profile-cases
  [options]
  (let [interested (interested-state 1000)
        blocks (block-width-state 1000)
        interested-source
        (profile-workload!
         (merge {:event :cpu :title "Pavlov: 1000 interested bthreads"}
                options)
         #(dotimes [_ 30]
            (run-core-state interested 100)))
        block-source
        (profile-workload!
         (merge {:event :cpu :title "Pavlov: bid with 1000 blocks"}
                options)
         #(dotimes [_ 300]
            (run-core-state blocks 100)))]
    [interested-source block-source]))

(defn profile!
  "Generate CPU flamegraphs for high-fan-out notification and wide blocking."
  []
  (validate!)
  (let [[interested-source block-source] (profile-cases nil)]
    {:interested-bthreads
     (copy-profile! interested-source "interested-1000-cpu.html")
     :block-width
     (copy-profile! block-source "block-width-1000-cpu.html")}))

(defn profile-collapsed!
  "Retain collapsed CPU stacks so profile percentages are reproducible."
  []
  (validate!)
  (let [[interested-source block-source]
        (profile-cases {:generate-flamegraph? false})]
    {:interested-bthreads
     (copy-profile! interested-source
                    "interested-1000-after-selection-cpu-collapsed.txt")
     :block-width
     (copy-profile! block-source
                    "block-width-1000-after-selection-cpu-collapsed.txt")}))

(defn -main
  [& [command arg]]
  (try
    (case command
      "validate" (prn (validate!))
      "benchmark" (do (benchmark! (if arg (Double/parseDouble arg) 2.0))
                      (println "Wrote dev/bench/results.edn"))
      "execute-baseline" (do (benchmark-execute-baseline!
                              (if arg (Double/parseDouble arg) 5.0))
                             (println "Wrote execute baseline/allocation evidence"))
      "regression-baseline" (do (benchmark-regressions!
                                 "dev/bench/regression-baseline.edn"
                                 (if arg (Double/parseDouble arg) 2.0))
                                (println "Wrote dev/bench/regression-baseline.edn"))
      "profile" (pprint/pprint (profile!))
      (println (str "Usage: validate | benchmark [limit-time-seconds] | "
                    "execute-baseline [limit-time-seconds] | "
                    "regression-baseline [limit-time-seconds] | profile")))
    (finally
      ;; Allow command-line runs that used futures to terminate promptly.
      ;; REPL calls do not invoke -main or shut down its agents.
      (shutdown-agents))))
