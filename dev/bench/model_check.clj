(ns bench.model-check
  "Benchmark suite and equivalence oracle for the model checker.

  An *implementation* is a map:

    {:id    keyword
     :lts   (fn [bthreads opts] lts)      ; same contract as graph/->lts
     :check (fn [config] result)}         ; same contract as check/check

  `baseline` is a frozen copy of the pre-optimization checker (the
  `bench.legacy.*` namespaces). `current` wraps the live checker. Candidate
  implementations are compared against `baseline` with `oracle`
  (equivalence) and `benchmark!` (speed and memory). Benchmarks construct fresh bthreads on every run because bthreads
  are stateful.

  Typical REPL use:

    (validate!)                          ; oracle self-checks
    (oracle current :smoke)              ; live checker vs baseline
    (benchmark! [baseline current] :standard \"phase1\")
    (profile! current :workers-6x4 :cpu)

  Results are written under dev/bench/model-check/ and are not committed."
  (:require [clj-async-profiler.core :as profiler]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [bench.legacy.graph :as legacy-graph]
            [bench.legacy.model.check :as legacy-check]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.graph :as graph]
            [tech.thomascothran.pavlov.model.check :as check])
  (:import (java.lang.management ManagementFactory)))

;; ---------------------------------------------------------------------------
;; Model families

(def done-event {:type :done :terminal true})

(defn- ev
  [prefix i j]
  (keyword (str prefix i "-" j)))

(defn- worker-events
  [n k]
  (set (for [i (range n) j (range k)] (ev "w" i j))))

(defn- last-events
  [n k]
  (set (for [i (range n)] (ev "w" i (dec k)))))

(defn worker
  "Request :wI-0 … :wI-(k-1) in sequence."
  [i k]
  (b/bids (for [j (range k)] {:request #{(ev "w" i j)}})))

(defn- workers
  [n k]
  (into {} (for [i (range n)] [(keyword (str "worker" i)) (worker i k)])))

(defn ordered-finish
  "`b/after-all`; its state records arrival order, inflating the state space."
  [targets]
  (b/after-all targets (fn [_] {:request #{done-event}})))

(defn canonical-finish
  "Order-insensitive finish: state is the set of arrived event types."
  [targets]
  (b/step (fn [seen event]
            (let [seen (cond-> (or seen #{}) event (conj (e/type event)))]
              [seen (if (= seen targets)
                      {:request #{done-event}}
                      {:wait-on targets})]))))

(defn count-observer
  "Fan-in observer that adds no states: its count is implied by the workers.
  Makes every event syntactically dependent (it waits on all of them)."
  [events]
  (b/step (fn [c event]
            [(if event (inc (or c 0)) 0) {:wait-on events}])))

(defn last-mover-observer
  "Fan-in observer that does split states: it remembers the last mover."
  [events]
  (b/step (fn [_ event]
            [(some-> event e/type name (str/split #"-") first)
             {:wait-on events}])))

(defn workers-model
  "n independent workers of k steps plus a finish bthread.

  Options:
  - :finish   :ordered (default) or :canonical
  - :observer nil, :count, or :last-mover"
  [n k {:keys [finish observer] :or {finish :ordered}}]
  (let [targets (last-events n k)
        events (worker-events n k)]
    {:bthreads (cond-> (assoc (workers n k)
                              :finish (case finish
                                        :ordered (ordered-finish targets)
                                        :canonical (canonical-finish targets)))
                 (= observer :count) (assoc :obs (count-observer events))
                 (= observer :last-mover) (assoc :obs (last-mover-observer events)))}))

(defn branching-workers-model
  "n workers each choosing :a or :b for k steps; no terminal, one deadlock."
  [n k]
  {:bthreads (into {}
                   (for [i (range n)]
                     [(keyword (str "w" i))
                      (b/bids (for [j (range k)]
                                {:request #{(ev "a" i j) (ev "b" i j)}}))]))})

(defn tree-model
  "Binary choice tree of depth d with history: 2^d deadlocked leaves.
  Stresses per-violation path reconstruction."
  [d]
  {:bthreads
   {:chooser (b/step (fn [history event]
                       (let [history (cond-> (or history []) event (conj (e/type event)))]
                         [history (if (< (count history) d)
                                    {:request #{:l :r}}
                                    {:wait-on #{:never}})])))}})

(defn safety-workers-model
  "Workers plus a safety bthread that flags worker 0 finishing before
  worker 1. The violation stays requested, so many edges violate."
  [n k]
  (let [w0 (ev "w" 0 (dec k))
        w1 (ev "w" 1 (dec k))
        violation {:type :w0-before-w1 :invariant-violated true :terminal true}]
    (assoc (workers-model n k {:finish :canonical})
           :safety-bthreads
           {:order (b/step (fn [s event]
                             (let [s (cond-> (or s #{}) event (conj (e/type event)))]
                               [s (if (and (s w0) (not (s w1)))
                                    {:request #{violation}}
                                    {:wait-on #{w0 w1}})])))})))

(defn philosophers-model
  "Dining philosophers, one meal each, then terminal. Forks block takes
  while held. Has deadlocks (everyone holds their left fork)."
  [n]
  (let [take (fn [p f] (keyword (str "take-" p "-" f)))
        release (fn [p f] (keyword (str "release-" p "-" f)))
        left identity
        right #(mod (inc %) n)
        fork (fn [f]
               (let [takers [f (mod (dec f) n)]] ; philosophers adjacent to fork f
                 ;; Notified only on takes while free and on the release while
                 ;; held, so the event alone determines the next holder.
                 (b/step (fn [_ event]
                           (let [holder (when event
                                          (first (filter #(= (e/type event) (take % f))
                                                         takers)))]
                             [holder (if (nil? holder)
                                       {:wait-on (set (map #(take % f) takers))}
                                       {:wait-on #{(release holder f)}
                                        :block (set (map #(take % f) takers))})])))))
        philosopher (fn [p]
                      (b/bids [{:request #{(take p (left p))}}
                               {:request #{(take p (right p))}}
                               {:request #{(keyword (str "eat-" p))}}
                               {:request #{(release p (left p))}}
                               {:request #{(release p (right p))}}]))
        eats (set (for [p (range n)] (keyword (str "eat-" p))))]
    {:bthreads (merge (into {} (for [p (range n)] [(keyword (str "phil" p)) (philosopher p)]))
                      (into {} (for [f (range n)] [(keyword (str "fork" f)) (fork f)]))
                      {:finish (canonical-finish eats)})}))

(defn livelock-model
  "n workers interleaved with a choice between finishing normally and
  spinning forever while blocking the terminal event."
  [n k]
  (assoc-in (workers-model n k {:finish :canonical})
            [:bthreads :spinner]
            (b/step (fn [mode event]
                      (let [mode (case (some-> event e/type)
                                   nil :choose
                                   :spin :spinning
                                   :no-spin :done
                                   mode)]
                        [mode (case mode
                                :choose {:request #{:spin :no-spin}}
                                :spinning {:request #{:spin} :block #{:done}}
                                :done nil)])))))

(defn hot-cycle-model
  "A hot bthread that can retry forever; the terminal stays reachable, so
  this is a hot cycle but not a livelock."
  []
  {:bthreads {:hot (b/step (fn [_ event]
                             [nil (when-not (some-> event e/terminal?)
                                    {:request #{:retry done-event} :hot true})]))}})

(defn hot-deadlock-model
  []
  {:bthreads {:hot (b/bids [{:request #{:a}} {:wait-on #{:never} :hot true}])}})

(defn spawn-model
  "An environment picks :x, :y or :z N times. A stateless spawner starts a
  child per pick, named after the event type, that requests one ack and
  retires. Picking a type again while its child is alive replaces the child.
  Exercises spawning, retirement, and state keys that ignore `:bthreads`."
  [n]
  (let [picks #{:x :y :z}
        ack #(keyword (str "ack-" (name %)))]
    {:bthreads
     {:env (b/bids (concat (repeat n {:request picks})
                           [{:request #{done-event}}]))
      :spawner (b/step (fn [_ event]
                         [nil (cond-> {:wait-on picks}
                                (some-> event e/type picks)
                                (assoc :bthreads
                                       {[:child (e/type event)]
                                        (b/bids [{:request #{(ack (e/type event))}}])}))]))}}))

(defn impossible-model
  [n k]
  (assoc (workers-model n k {:finish :canonical}) :possible #{:w0-0 :never-happens}))

;; ---------------------------------------------------------------------------
;; Suite

(def ^:private unlimited 100000000)

(defn- bench
  [id tier make & {:as more}]
  (merge {:id id :tier tier :make make} more))

(def suite
  "Benchmarks by tier. :smoke (oracle, fast) ⊂ :standard ⊂ :large."
  [;; coverage: one or more check categories each
   (bench :coverage-safety :smoke #(safety-workers-model 3 2))
   (bench :coverage-livelock :smoke #(livelock-model 2 2))
   (bench :coverage-hot-cycle :smoke hot-cycle-model)
   (bench :coverage-hot-deadlock :smoke hot-deadlock-model)
   (bench :coverage-impossible :smoke #(impossible-model 2 2))
   (bench :philosophers-3 :smoke #(philosophers-model 3))
   (bench :workers-4x3 :smoke #(workers-model 4 3 {}))
   (bench :workers-4x3-count :smoke #(workers-model 4 3 {:observer :count}))
   (bench :workers-4x3-last :smoke #(workers-model 4 3 {:observer :last-mover}))
   (bench :branching-3x3 :smoke #(branching-workers-model 3 3))
   (bench :tree-6 :smoke #(tree-model 6))
   (bench :spawn-3 :smoke #(spawn-model 3))
   ;; standard: ~0.5–5 s on the baseline
   (bench :workers-6x4 :standard #(workers-model 6 4 {}))
   (bench :workers-6x4-canonical :standard #(workers-model 6 4 {:finish :canonical}))
   (bench :workers-6x4-count :standard #(workers-model 6 4 {:observer :count}))
   (bench :workers-5x4-last :standard #(workers-model 5 4 {:observer :last-mover}))
   (bench :branching-5x4 :standard #(branching-workers-model 5 4))
   (bench :safety-5x3 :standard #(safety-workers-model 5 3))
   (bench :philosophers-6 :standard #(philosophers-model 6))
   (bench :livelock-5x3 :standard #(livelock-model 5 3))
   (bench :tree-10 :standard #(tree-model 10))
   (bench :spawn-8 :standard #(spawn-model 8))
   ;; large: tens of seconds on the baseline
   (bench :workers-7x4 :large #(workers-model 7 4 {}))
   (bench :workers-7x4-count :large #(workers-model 7 4 {:observer :count}))
   (bench :philosophers-7 :large #(philosophers-model 7))
   (bench :tree-12 :large #(tree-model 12))])

(def ^:private tier-rank {:smoke 0 :standard 1 :large 2})

(defn select
  "Benchmarks for a tier (cumulative), keyword id, or collection of ids."
  [sel]
  (cond
    (tier-rank sel) (filter #(<= (tier-rank (:tier %)) (tier-rank sel)) suite)
    (keyword? sel) (filter #(= sel (:id %)) suite)
    (coll? sel) (filter #((set sel) (:id %)) suite)
    :else (throw (ex-info "Unknown selection" {:selection sel}))))

(defn config
  "A fresh check config for a benchmark."
  [{:keys [make]}]
  (merge {:max-nodes unlimited} (make)))

(defn- check-bthreads
  "Bthreads assembled exactly as `check/check` does."
  [cfg]
  (#'check/assemble-all-bthreads cfg))

;; ---------------------------------------------------------------------------
;; Implementations

(def baseline
  "The pre-optimization checker, frozen in `bench.legacy.*`."
  {:id :baseline
   :lts legacy-graph/->lts
   :check legacy-check/check})

(def current
  "The live checker."
  {:id :current
   :lts #'graph/->lts
   :check #'check/check})

(def implementations {:baseline baseline :current current})

;; ---------------------------------------------------------------------------
;; Canonical LTS
;;
;; Node ids are interner-assigned and depend on exploration order, so graphs
;; are compared up to isomorphism. The LTS is deterministic (one successor per
;; event per node), so a BFS from the root that visits each node's outgoing
;; edges in a fixed event order is a canonical labelling.

(defn- canonical-form
  [x]
  (let [by-str #(compare (pr-str %1) (pr-str %2))]
    (walk/postwalk (fn [v]
                     (cond
                       (map? v) (into (sorted-map-by by-str) v)
                       (set? v) (into (sorted-set-by by-str) v)
                       :else v))
                   x)))

(defn event-key
  "Stable string for an event, independent of map/set iteration order."
  [event]
  (pr-str (canonical-form event)))

(defn canonicalize
  "Relabel an LTS. Returns
  {:index {id canon-id} :nodes {canon-id payload} :succ {canon-id {ekey canon-id}}
   :edges #{[canon-id ekey canon-id]} :events {ekey event} :root 0 :unreachable n
   :truncated bool}"
  [{:keys [root nodes edges truncated]}]
  (let [ekeys (volatile! {})
        ekey (fn [event]
               (or (get @ekeys event)
                   (let [k (event-key event)] (vswap! ekeys assoc event k) k)))
        out (persistent!
             (reduce (fn [m {:keys [from event] :as edge}]
                       (assoc! m from (conj (get m from []) [(ekey event) edge])))
                     (transient {})
                     edges))
        index (loop [queue (conj clojure.lang.PersistentQueue/EMPTY root)
                     index {root 0}]
                (if-let [id (peek queue)]
                  (let [next-ids (->> (get out id) (sort-by first) (map (comp :to second)))
                        [queue index] (reduce (fn [[q idx] to]
                                                (if (contains? idx to)
                                                  [q idx]
                                                  [(conj q to) (assoc idx to (count idx))]))
                                              [(pop queue) index]
                                              next-ids)]
                    (recur queue index))
                  index))
        succ (reduce-kv (fn [m from pairs]
                          (if-let [cf (index from)]
                            (let [by-event (into {} (map (fn [[k {:keys [to]}]] [k (index to)])) pairs)]
                              (when (not= (count by-event) (count pairs))
                                (throw (ex-info "Nondeterministic LTS: duplicate event from node"
                                                {:node from})))
                              (assoc m cf by-event))
                            m))
                        {}
                        out)]
    {:index index
     :root 0
     :nodes (into {} (keep (fn [[id payload]] (when-let [c (index id)] [c payload]))) nodes)
     :succ succ
     :edges (into #{} (for [[f m] succ [k t] m] [f k t]))
     :events (into {} (map (fn [[ev k]] [k ev])) @ekeys)
     :unreachable (- (count nodes) (count (filter index (keys nodes))))
     :truncated (boolean truncated)}))

(defn- sample [n coll] (vec (take n coll)))

(defn normalize-payload
  "Node payload with spawned bthread objects reduced to their names. Bids
  that spawn hold fresh bthread instances, which compare by identity, so
  payloads from separate runs would otherwise never be equal."
  [payload]
  (if (and (map? payload) (map? (:bthread->bid payload)))
    (update payload :bthread->bid
            (fn [bthread->bid]
              (-> bthread->bid
                      (update-vals (fn [bid]
                                     (if (and (map? bid) (:bthreads bid))
                                       (update bid :bthreads #(set (keys %)))
                                       bid))))))
    payload))

(defn- normalize-witness-state
  [witness]
  (update witness :state normalize-payload))

(defn compare-lts
  "Compare two LTSs up to isomorphism. Returns {:equal? bool ...details}.

  Options:
  - :payload-keys  node payload keys to compare (default: all; [] to skip)"
  ([a b] (compare-lts a b {}))
  ([a b {:keys [payload-keys]}]
   (let [ca (canonicalize a)
         cb (canonicalize b)
         project (comp (if payload-keys #(select-keys % payload-keys) identity)
                       normalize-payload)
         missing (remove (:edges cb) (:edges ca))
         extra (remove (:edges ca) (:edges cb))
         payload-diffs (when (empty? missing)
                         (for [[c pa] (:nodes ca)
                               :let [pb (get-in cb [:nodes c])]
                               :when (not= (project pa) (project pb))]
                           c))
         details {:nodes [(count (:nodes ca)) (count (:nodes cb))]
                  :edges [(count (:edges ca)) (count (:edges cb))]
                  :truncated [(:truncated ca) (:truncated cb)]
                  :unreachable [(:unreachable ca) (:unreachable cb)]
                  :missing-edges (sample 5 missing)
                  :extra-edges (sample 5 extra)
                  :payload-diffs (sample 5 payload-diffs)}]
     (assoc details :equal?
            (and (apply = (:nodes details))
                 (apply = (:truncated details))
                 (= [0 0] (:unreachable details))
                 (empty? missing)
                 (empty? extra)
                 (empty? payload-diffs))))))

;; ---------------------------------------------------------------------------
;; Check-result views

(defn- replay-types
  "Canonical node ids reachable from `from` along a path of event types."
  [{:keys [succ events]} from types]
  (reduce (fn [ids t]
            (into #{} (for [id ids [k to] (get succ id)
                            :when (= t (e/type (get events k)))]
                        to)))
          #{from}
          types))

(defn- replay-events
  [{:keys [succ]} from evs]
  (reduce (fn [id ev] (when id (get-in succ [id (event-key ev)]))) from evs))

(defn- terminal-targets
  [{:keys [edges events]}]
  (into #{} (keep (fn [[_ k t]] (when (e/terminal? (get events k)) t))) edges))

(defn- reaches-terminal
  [{:keys [edges] :as c}]
  (let [rev (reduce (fn [m [f _ t]] (update m t (fnil conj []) f)) {} edges)]
    (loop [seen (terminal-targets c) stack (vec seen)]
      (if-let [x (peek stack)]
        (let [new (into [] (remove seen) (rev x))]
          (recur (into seen new) (into (pop stack) new)))
        seen))))

(defn- depths
  [{:keys [succ root]}]
  (loop [queue (conj clojure.lang.PersistentQueue/EMPTY root) d {root 0}]
    (if-let [id (peek queue)]
      (let [new (remove d (vals (get succ id)))]
        (recur (into (pop queue) new) (into d (map (fn [x] [x (inc (d id))])) new)))
      d)))

(defn- leaf? [{:keys [succ]} id] (empty? (get succ id)))

(defn- witness-errors
  "Validate every witness in `result` against its own LTS. Returns a seq of problems."
  [result c]
  (let [{:keys [index nodes root]} c
        term (terminal-targets c)
        depth (depths c)]
    (concat
     (for [{:keys [path state]} (:deadlocks result)
           :let [ends (replay-types c root path)
                 ok (some #(and (leaf? c %) (not (term %)) (= state (nodes %))) ends)]
           :when (not ok)]
       {:category :deadlocks :problem :invalid-witness :path path})
     (for [{:keys [path event state]} (:safety-violations result)
           :let [from (replay-events c root path)
                 to (when from (get-in c [:succ from (event-key event)]))]
           :when (not (and to (= state (nodes to)) (= (count path) (depth from))))]
       {:category :safety-violations :problem :invalid-witness :event event})
     (for [{:keys [path cycle]} (:livelocks result)
           :let [reach (reaches-terminal c)
                 entries (replay-types c root path)
                 ok (some #(and (not (reach %)) ((replay-types c % cycle) %)) entries)]
           :when (not ok)]
       {:category :livelocks :problem :invalid-witness :path path :cycle cycle})
     (when-let [{:keys [node-id path-edges cycle-edges state]} (:liveness-violation result)]
       (let [chain (map (fn [{:keys [from to event]}]
                          [(index from) (event-key event) (index to)])
                        (concat path-edges cycle-edges))
             target (index node-id)
             ok (and (every? (:edges c) chain)
                     (= root (if (seq path-edges) (first (first chain)) target))
                     (every? (fn [[[_ _ t] [f]]] (= t f)) (partition 2 1 chain))
                     (= state (nodes target))
                     (or (empty? cycle-edges)
                         (= target (last (last chain))))
                     (:hot (nodes target)))]
         (when-not ok
           [{:category :liveness-violation :problem :invalid-witness}]))))))

(defn- liveness-kind
  [{:keys [cycle-edges path-edges]}]
  (cond
    (seq cycle-edges) :hot-cycle
    (e/terminal? (:event (peek (vec path-edges)))) :hot-terminal
    :else :hot-deadlock))

(defn semantic-view
  "Implementation-independent summary of a check result. Witness choice is
  abstracted to path lengths (deadlocks and safety violations must use
  shortest paths) and presence (livelock and liveness report at most one)."
  [result]
  {:categories (set (keys result))
   :truncated (:truncated result)
   :impossible (:impossible result)
   :deadlocks (frequencies (map (fn [{:keys [path state]}]
                                  [(count path) (normalize-payload state)])
                                (:deadlocks result)))
   :safety-violations (frequencies (map (fn [{:keys [path event state]}]
                                          [(count path) (event-key event)
                                           (normalize-payload state)])
                                        (:safety-violations result)))
   :livelock? (boolean (seq (:livelocks result)))
   :liveness (some-> (:liveness-violation result) liveness-kind)})

(defn strict-view
  "Full result with node ids relabelled canonically and witness vectors
  turned into multisets. Equal strict views mean identical output."
  [result c]
  (let [relabel (fn [edges] (mapv #(-> % (update :from (:index c)) (update :to (:index c))) edges))]
    (cond-> (-> result
                (update :deadlocks #(some->> % (map normalize-witness-state) frequencies))
                (update :safety-violations #(some->> % (map normalize-witness-state) frequencies)))
      (:liveness-violation result)
      (update :liveness-violation
              #(-> %
                   normalize-witness-state
                   (update :node-id (:index c))
                   (update :path-edges relabel)
                   (update :cycle-edges relabel))))))

(defn compare-results
  "Compare two check results (each with :lts metadata, or nil)."
  [ra rb]
  (let [lts-of (fn [r] (or (:lts (meta r)) {:root nil :nodes {} :edges []}))
        ca (canonicalize (lts-of ra))
        cb (canonicalize (lts-of rb))
        sa (semantic-view ra)
        sb (semantic-view rb)
        errors (concat (map #(assoc % :side :a) (witness-errors ra ca))
                       (map #(assoc % :side :b) (witness-errors rb cb)))]
    {:semantic? (= sa sb)
     :identical? (= (strict-view ra ca) (strict-view rb cb))
     :witnesses-valid? (empty? errors)
     :witness-errors (sample 5 errors)
     :semantic-diff (when (not= sa sb)
                      (into {} (for [k (keys sa) :when (not= (sa k) (sb k))]
                                 [k {:a (sa k) :b (sb k)}])))}))

;; ---------------------------------------------------------------------------
;; Oracle

(defn oracle
  "Compare `candidate` with `reference` (default `baseline`) on a selection.

  Modes:
  - :exact      LTS isomorphic incl. payloads, results semantically equal,
                all witnesses valid (default)
  - :reduction  results agree on violation categories and witnesses are valid
                (for POR and other state-space reductions)

  Returns a vector of per-benchmark reports with :pass?."
  ([candidate sel] (oracle candidate sel {}))
  ([candidate sel {:keys [reference mode payload-keys]
                   :or {reference baseline mode :exact}}]
   (vec
    (for [bm (select sel)]
      (let [cfg-a (config bm)
            cfg-b (config bm)
            lts-cmp (when (= mode :exact)
                      (compare-lts ((:lts reference) (check-bthreads cfg-a) cfg-a)
                                   ((:lts candidate) (check-bthreads cfg-b) cfg-b)
                                   {:payload-keys payload-keys}))
            res-cmp (compare-results ((:check reference) (config bm))
                                     ((:check candidate) (config bm)))
            categories-agree? (= (get-in res-cmp [:semantic-diff :categories]) nil)
            pass? (case mode
                    :exact (and (:equal? lts-cmp) (:semantic? res-cmp) (:witnesses-valid? res-cmp))
                    :reduction (and categories-agree? (:witnesses-valid? res-cmp)))]
        (cond-> {:id (:id bm) :pass? (boolean pass?) :identical? (:identical? res-cmp)}
          (not pass?) (assoc :lts lts-cmp :result res-cmp)))))))

(defn oracle-summary
  [reports]
  {:pass (count (filter :pass? reports))
   :fail (mapv :id (remove :pass? reports))
   :not-identical (mapv :id (remove :identical? reports))})

;; ---------------------------------------------------------------------------
;; Self-validation

(defn- shuffled-lts
  "Same graph, different ids and edge order: must compare equal."
  [{:keys [root nodes edges] :as lts}]
  (let [ids (keys nodes)
        perm (zipmap ids (shuffle (range 1000000 (+ 1000000 (count ids)))))]
    (assoc lts
           :root (perm root)
           :nodes (update-keys nodes perm)
           :edges (shuffle (mapv #(-> % (update :from perm) (update :to perm)) edges)))))

(defn validate!
  "Check the oracle itself: baseline ≡ baseline, isomorphic relabelling is
  accepted, and dropped edges / changed payloads / bad witnesses are caught.
  Also checks that the coverage benchmarks exercise every category."
  []
  (let [self (oracle baseline :smoke)
        bm (first (select :workers-4x3))
        cfg (config bm)
        lts (legacy-graph/->lts (check-bthreads cfg) cfg)
        dropped (update lts :edges #(vec (rest %)))
        some-id (first (keys (:nodes lts)))
        repayloaded (assoc-in lts [:nodes some-id :hot] :changed)
        deadlocked (legacy-check/check (config (first (select :tree-6))))
        bad-witness (update-in deadlocked [:deadlocks 0 :path] #(vec (reverse (conj % :l))))
        categories (->> (select :smoke)
                        (map #(set (keys (legacy-check/check (config %)))))
                        (apply set/union))
        checks {:self-oracle (empty? (:fail (oracle-summary self)))
                :self-identical (empty? (:not-identical (oracle-summary self)))
                :isomorphism-accepted (:equal? (compare-lts lts (shuffled-lts lts)))
                :dropped-edge-caught (not (:equal? (compare-lts lts dropped)))
                :payload-change-caught (not (:equal? (compare-lts lts repayloaded)))
                :bad-witness-caught (not (:witnesses-valid?
                                          (compare-results deadlocked bad-witness)))
                :all-categories-covered (= #{:deadlocks :safety-violations :livelocks
                                             :liveness-violation :impossible}
                                           (disj categories :truncated))}]
    (when-not (every? val checks)
      (throw (ex-info "Oracle validation failed" {:checks checks :self self})))
    checks))

;; ---------------------------------------------------------------------------
;; Measurement

(defn- used-bytes
  []
  (dotimes [_ 3] (System/gc))
  (let [rt (Runtime/getRuntime)]
    (- (.totalMemory rt) (.freeMemory rt))))

(def ^:private ^com.sun.management.ThreadMXBean thread-mx
  (ManagementFactory/getThreadMXBean))

(defn- allocated-bytes
  "Bytes allocated by all live threads (parallel-safe, approximate)."
  []
  (reduce + (remove neg? (.getThreadAllocatedBytes thread-mx (.getAllThreadIds thread-mx)))))

(defn- gc-millis
  []
  (reduce + (map #(.getCollectionTime ^java.lang.management.GarbageCollectorMXBean %)
                 (ManagementFactory/getGarbageCollectorMXBeans))))

(defn- run-once
  "Run phase (:lts or :check) once; returns timing, allocation, GC, and result."
  [impl phase bm]
  (let [cfg (config bm)
        f (case phase
            :lts #((:lts impl) (check-bthreads cfg) cfg)
            :check #((:check impl) cfg))
        alloc0 (allocated-bytes)
        gc0 (gc-millis)
        t0 (System/nanoTime)
        result (f)
        t1 (System/nanoTime)]
    {:ms (/ (- t1 t0) 1e6)
     :alloc-mb (/ (- (allocated-bytes) alloc0) 1048576.0)
     :gc-ms (- (gc-millis) gc0)
     :result result}))

(defn- median [xs] (let [v (vec (sort xs))] (nth v (quot (count v) 2))))

(defn- lts-size
  [lts]
  {:nodes (count (:nodes lts)) :edges (count (:edges lts))})

(defn measure
  "Measure one benchmark for one implementation.

  Runs each phase `warmup` times, then `runs` times, reporting the median.
  Retained memory is measured for the LTS phase only."
  [impl bm {:keys [warmup runs phases] :or {warmup 1 runs 3 phases [:lts :check]}}]
  (println "BENCH" (:id impl) (:id bm))
  (into {:impl (:id impl) :bench (:id bm)}
        (for [phase phases]
          (let [_ (dotimes [_ warmup] (run-once impl phase bm))
                samples (vec (repeatedly runs #(run-once impl phase bm)))
                result (:result (peek samples))
                before (when (= phase :lts) (used-bytes))
                retained (when (= phase :lts)
                           (let [r (:result (run-once impl phase bm))
                                 after (used-bytes)]
                             (when (map? r) (- after before))))]
            [phase (cond-> {:median-ms (median (map :ms samples))
                            :min-ms (apply min (map :ms samples))
                            :median-alloc-mb (median (map :alloc-mb samples))
                            :median-gc-ms (median (map :gc-ms samples))}
                     (= phase :lts) (merge (lts-size result)
                                           {:retained-mb (some-> retained (/ 1048576.0))})
                     (= phase :check) (assoc :categories (set (keys result))))]))))

(defn environment
  []
  (let [rt (Runtime/getRuntime)]
    {:timestamp (str (java.time.Instant/now))
     :git-head (str/trim (:out (shell/sh "git" "rev-parse" "--short" "HEAD")))
     :java-version (System/getProperty "java.version")
     :clojure-version (clojure-version)
     :available-processors (.availableProcessors rt)
     :max-heap-mb (quot (.maxMemory rt) 1048576)
     :jvm-args (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean)))}))

(def results-dir "dev/bench/model-check")

(defn- write-edn!
  [filename data]
  (let [f (io/file results-dir filename)]
    (.mkdirs (.getParentFile f))
    (spit f (with-out-str (pprint/pprint data)))
    (.getPath f)))

(defn speedups
  "Baseline-relative speedups from a `benchmark!` result."
  [{:keys [results]}]
  (let [by (group-by :bench results)]
    (for [[bench rows] by
          :let [base (first (filter #(= :baseline (:impl %)) rows))]
          row rows
          :when (not= row base)]
      {:bench bench
       :impl (:impl row)
       :lts-speedup (some-> base :lts :median-ms (/ (get-in row [:lts :median-ms])))
       :check-speedup (some-> base :check :median-ms (/ (get-in row [:check :median-ms])))
       :retained-ratio (some-> base :lts :retained-mb (/ (get-in row [:lts :retained-mb])))})))

(defn benchmark!
  "Measure `impls` on a selection and write dev/bench/model-check/<label>.edn.
  Run oracle first; this does not check correctness."
  ([impls sel label] (benchmark! impls sel label {}))
  ([impls sel label opts]
   (let [results (vec (for [bm (select sel) impl impls] (measure impl bm opts)))
         evidence {:environment (environment)
                   :selection sel
                   :options opts
                   :results results}
         path (write-edn! (str label ".edn") evidence)]
     (pprint/print-table [:bench :impl :nodes :edges :lts-ms :check-ms :retained-mb]
                         (for [{:keys [bench impl lts check]} results]
                           {:bench bench :impl impl
                            :nodes (:nodes lts) :edges (:edges lts)
                            :lts-ms (some-> (:median-ms lts) Math/round)
                            :check-ms (some-> (:median-ms check) Math/round)
                            :retained-mb (some-> (:retained-mb lts) Math/round)}))
     (when (< 1 (count impls))
       (pprint/print-table (speedups evidence)))
     (println "Wrote" path)
     evidence)))

;; ---------------------------------------------------------------------------
;; Profiling

(defn profile!
  "Profile one benchmark phase with async-profiler.

  `event` is `:cpu` or `:alloc`. Writes a flamegraph and collapsed stacks to
  dev/bench/model-check/profiles/. Returns their paths."
  ([impl bench-id event] (profile! impl bench-id event {}))
  ([impl bench-id event {:keys [phase repeat] :or {phase :check repeat 1}}]
   (let [bm (or (first (select bench-id))
                (throw (ex-info "Unknown benchmark" {:id bench-id})))
         stem (str (name (:id impl)) "-" (name bench-id) "-" (name phase) "-" (name event))
         dir (io/file results-dir "profiles")
         workload #(dotimes [_ repeat] (run-once impl phase bm))
         capture (fn [opts]
                   (workload) ; warm
                   (profiler/start (merge {:event event} opts))
                   (try (workload)
                        (catch Throwable t
                          (profiler/stop opts)
                          (throw t)))
                   (profiler/stop opts))
         copy (fn [src suffix]
                (let [t (io/file dir (str stem suffix))]
                  (.mkdirs dir)
                  (io/copy (io/file (str src)) t)
                  (.getPath t)))]
     {:flamegraph (copy (capture {:title stem}) ".html")
      :collapsed (copy (capture {:generate-flamegraph? false}) "-collapsed.txt")})))

;; ---------------------------------------------------------------------------
;; CLI

(defn -main
  "clj -M:dev -m bench.model-check validate
   clj -M:dev -m bench.model-check oracle [tier]              ; current vs baseline
   clj -M:dev -m bench.model-check benchmark [tier] [label]   ; baseline and current
   clj -M:dev -m bench.model-check profile <bench-id> [cpu|alloc] [baseline|current]"
  [& [command a b c]]
  (try
    (case command
      "validate" (pprint/pprint (validate!))
      "oracle" (pprint/pprint (oracle-summary (oracle current (keyword (or a "smoke")))))
      "benchmark" (do (validate!)
                      (benchmark! [baseline current] (keyword (or a "standard")) (or b "current")))
      "profile" (pprint/pprint (profile! (implementations (keyword (or c "current"))) (keyword a) (keyword (or b "cpu"))))
      (println (:doc (meta #'-main))))
    (finally
      (shutdown-agents))))
