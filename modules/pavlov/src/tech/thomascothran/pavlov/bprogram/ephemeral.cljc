(ns tech.thomascothran.pavlov.bprogram.ephemeral
  "Run behavioral programs (bprograms) that coordinate bthreads.

   This namespace provides two main entry points:

   - `execute!` - Run a bprogram and get a promise of the terminal event.
     Use this when you want to treat a bprogram like a function call.

   - `make-program!` - Create a bprogram you can interact with via
     `submit-event!`, `subscribe!`, and `stop!`. Use this for long-running
     or interactive programs.

   ## Event Selection Algorithm

   At each step of the bprogram, the bpgrogram:

   1. Notifies all bthreads that are subscribed to the previous event:
      those that have either requested or waited-on that event type.
   2. Those bthreads submit new bids
   3. Those bids are added into the previous bids from all the other
      bthreads that are active and were not subscribed to the previous event
   4. The next event is chosen based on the following selection algorithm:
      i. The highest priority bid (based on the bthread priority)
         with an *unblocked* request is selected
      ii. From that bid, the highest priority *unblocked* requested event
          is selected

   If no event is selected, `execute!` will terminate the program. `make-program!`,
   on the other hand, returns a bprogram that continues to wait for external events

   ## Bthread Priority

   Bthreads can be provided as either:

   - **Map** (non-deterministic priority): `{:bt1 bthread1, :bt2 bthread2}`
   - **Vector of tuples** (deterministic priority): `[[:bt1 bthread1] [:bt2 bthread2]]`

   When using a vector, earlier bthreads have higher priority. This matters
   when multiple bthreads request events—the highest priority bthread's
   request is selected first.

   ## Example

   ```clojure
   (require '[tech.thomascothran.pavlov.bthread :as b])
   (require '[tech.thomascothran.pavlov.bprogram.ephemeral :as bpe])

   ;; Simple execution - returns promise of terminal event
   @(bpe/execute!
     [[:step-1 (b/bids [{:request #{:a}}])]
      [:step-2 (b/bids [{:wait-on #{:a}}
                        {:request #{{:type :done :terminal true}}}])]]
     {:kill-after 1000})
   ;; => {:type :done, :terminal true}
   ```

   ## Requested event priority

   Within a bid, the request set can either be an ordered sequence or
   an unordered set. If the sequence is ordered, the requested, unblocked
   events are ordered from high to low priority. If the requests are a
   set then priority is non-deterministic.

   Note that unordered requested events are useful for creating a branching
   choice when working with the model checker or the visualizing possible
   paths through the program.

   ## Related

   See `tech.thomascothran.pavlov.bthread` for creating bthreads."
  (:refer-clojure :exclude [run!])
  (:require [tech.thomascothran.pavlov.bprogram.proto :as bprogram]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.event.publisher.defaults :as pub-default]
            [tech.thomascothran.pavlov.event.publisher.proto :as pub]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram.state
             :as state])
  #?(:clj (:import (java.util.concurrent LinkedBlockingQueue)
                   (java.util.concurrent Executors TimeUnit))))

;; move this elsewhere
#?(:clj (extend-protocol bprogram/BProgramQueue
          LinkedBlockingQueue
          (conj [this event]
            (.put this event))
          (pop [this] (.take this))))

#?(:cljs
   (defn deliver
     [m v]
     ((get m :resolve) v)))

(defn- set-stopped!
  [program-opts terminal-event]
  (deliver (get program-opts :stopped)
           terminal-event))

(defn- set-killed!
  [program-opts]
  (deliver (get program-opts :killed) true))

#?(:cljs
   (defn- deferred-promise
     []
     (let [resolve (volatile! nil)
           reject (volatile! nil)]
       {:promise
        (js/Promise. (fn [resolve' reject']
                       (vreset! resolve resolve')
                       (vreset! reject reject')))
        :resolve @resolve
        :reject @reject})))

(defn- handle-event!
  [bprogram program-opts event]
  (loop [event' event
         subscriber-requested-events' []]
    (let [!state (get program-opts :!state)
          publisher (get program-opts :publisher)
          state @!state
          next-state (reset! !state (state/step state event'))
          next-event (get next-state :next-event)
          terminate? (event/terminal? event')
          recur? (and next-event (not terminate?))

          notification-result
          (pub/notify! publisher event' bprogram)

          subscriber-requested-events
          (when event
            (into []
                  (comp (map :event)
                        (filter identity))
                  notification-result))]

      (cond recur?
            (recur next-event (into subscriber-requested-events
                                    subscriber-requested-events'))

            terminate?
            (set-stopped! program-opts event')

            :else
            (doseq [requested-event subscriber-requested-events']
              (bprogram/submit-event! bprogram requested-event))))))

#?(:clj
   (defn- submit-event!
     [_ opts event]
     (let [in-queue (get opts :in-queue)]
       (bprogram/conj in-queue event)))

   :cljs
   (defn- submit-event!
     [bprogram opts event]
     (js/setTimeout #(handle-event! bprogram opts event) 0)))

#?(:clj (defn- run-event-loop!
          [bprogram program-opts]
          (let [killed (get program-opts :killed)
                in-queue (get program-opts :in-queue)]
            (loop [next-event' (some-> program-opts
                                       (get :!state)
                                       deref
                                       (get :next-event))]
              (when-not (realized? killed)
                (when next-event'
                  (handle-event! bprogram program-opts next-event'))
                (when-not (event/terminal? next-event')
                  (recur (bprogram/pop in-queue))))))))

(defn kill!
  [program-opts]
  (set-killed! program-opts)
  (set-stopped! program-opts {:type :pavlov/kill
                              :terminal true})
  #?(:clj (get program-opts :killed)
     :cljs (get-in program-opts [:killed :promise])))

(defn- subscribe!
  [program k subscriber]
  (let [publisher (get program :publisher)]
    (pub/subscribe! publisher k subscriber)))

(defn- stop!
  [bprogram program-opts]
  (submit-event! bprogram
                 program-opts
                 {:type :pavlov/terminate
                  :terminal true})
  #?(:clj (get program-opts :stopped)
     :cljs (get-in program-opts [:stopped :promise])))

(defn make-program!
  "Create a long-running behavioral program from bthreads.

  Use this when you need to interact with the program over time via
  `submit-event!`, `subscribe!`, or `stop!`. For simpler fire-and-forget
  execution, see `execute!`.

  Parameters
  ----------
  - `named-bthreads` - bthreads with names, as either:
    + Map (non-deterministic priority): `{:name1 bthread1, :name2 bthread2}`
    + Vector of tuples (deterministic priority): `[[:name1 bthread1] [:name2 bthread2]]`

  - `opts` - optional map:
    + `:subscribers` - map of subscriber-name to `(fn [event bprogram] ...)`.
      Subscribers can return `{:event some-event}` to inject events.
    + `:publisher` - custom event publisher (advanced)
    + `:in-queue` - custom input queue (advanced, CLJ only)

  Returns the bprogram, which implements:
  - `(bprogram/submit-event! program event)` - send an external event
  - `(bprogram/subscribe! program key f)` - add a subscriber
  - `(bprogram/stop! program)` - gracefully terminate, returns promise
  - `(bprogram/kill! program)` - force terminate, returns promise
  - `(bprogram/bthread->bids program)` - introspect current bids

  Example
  -------
  ```clojure
  (require '[tech.thomascothran.pavlov.bthread :as b])
  (require '[tech.thomascothran.pavlov.bprogram.ephemeral :as bpe])
  (require '[tech.thomascothran.pavlov.bprogram.proto :as bp])

  (def program
    (bpe/make-program!
      [[:greeter (b/on :greet
                       (fn [e] {:request #{{:type :greeted}}}))]]
      {:subscribers {:logger (fn [e _] (println \"Event:\" e))}}))

  (bp/submit-event! program {:type :greet})
  @(bp/stop! program)
  ;; => {:type :pavlov/terminate, :terminal true}
  ```"
  ([named-bthreads] (make-program! named-bthreads nil))
  ([named-bthreads opts]
   (let [initial-state (state/init named-bthreads)
         !state (atom initial-state)
         in-queue (get opts :in-queue #?(:clj (LinkedBlockingQueue.)))
         subscribers (get opts :subscribers {})
         publisher (get opts :publisher
                        (pub-default/make-publisher! {:subscribers subscribers}))

         stopped #?(:clj (promise)
                    :cljs (deferred-promise))

         program-opts
         {:!state !state
          :in-queue in-queue
          :stopped stopped
          :killed #?(:clj (promise)
                     :cljs (deferred-promise))
          :publisher publisher}

         bprogram (reify
                    bprogram/BProgram
                    (stop! [this] (stop! this program-opts))
                    (kill! [_] (kill! program-opts))
                    (stopped [_] stopped)
                    (subscribe! [_ k f]
                      (pub/subscribe! publisher k f))
                    (submit-event! [this event]
                      (submit-event! this program-opts event))

                    bprogram/BProgramIntrospectable
                    (bthread->bids [_]
                      (get @!state :bthread->bid)))]

     #?(:clj (future (run-event-loop! bprogram program-opts))
        :cljs (when-let [next-event (get initial-state :next-event)]
                (submit-event! bprogram program-opts next-event)))
     bprogram)))

(defn execute!
  "Execute a behavioral program and return a promise of the terminal event.

  This is the simplest way to run a bprogram. It handles the full lifecycle:
  starts the program, runs until termination, and returns the final event.
  Automatically adds a deadlock detector that terminates if no events can
  be selected.

  Parameters
  ----------
  - `bthreads` - bthreads with names, as either:
    + Map (non-deterministic priority): `{:name1 bthread1, :name2 bthread2}`
    + Vector of tuples (deterministic priority): `[[:name1 bthread1] [:name2 bthread2]]`

  - `opts` - optional map:
    + `:kill-after` - milliseconds after which to force-terminate the program
    + `:request-event` - an event to request at startup (kicks off the program)
    + `:subscribers` - map of subscriber-name to `(fn [event bprogram] ...)`

  Returns a promise/future that delivers the terminal event when the program
  ends. Dereference with `@` or `deref` to block until completion.

  Termination occurs when:
  - A bthread requests an event with `:terminal true`
  - No unblocked events can be selected (deadlock)
  - `:kill-after` timeout expires

  Example with deterministic priority
  -----------------------------------
  ```clojure
  (require '[tech.thomascothran.pavlov.bthread :as b])
  (require '[tech.thomascothran.pavlov.bprogram.ephemeral :as bpe])

  ;; Vector of tuples gives :producer priority over :consumer
  @(bpe/execute!
    [[:producer (b/bids [{:request #{:data-ready}}])]
     [:consumer (b/bids [{:wait-on #{:data-ready}}
                         {:request #{{:type :done :terminal true}}}])]]
    {:kill-after 1000})
  ;; => {:type :done, :terminal true}
  ```

  Example with non-deterministic priority
  ---------------------------------------
  ```clojure
  ;; Map has non-deterministic priority - either bthread could go first
  @(bpe/execute!
    {:bt-a (b/bids [{:request #{:event-a}}])
     :bt-b (b/bids [{:request #{:event-b}}])}
    {:kill-after 1000})
  ;; => {:type :tech.thomascothran.pavlov.bprogram.ephemeral/deadlock, :terminal true}
  ```"
  ([bthreads]
   (execute! bthreads nil))
  ([bthreads opts]
   (let [requested-event (get opts :request-event)

         requested-event-bthread
         (when requested-event
           (b/bids [{:request #{requested-event}}]))

         kill-after (get opts :kill-after)

         bthreads'
         (cond-> (into [] bthreads)
           requested-event-bthread
           (conj [::requested-event requested-event-bthread])

           :then
           (conj [::deadlock {:request #{{:type ::deadlock
                                          :terminal true}}}]))

         bprogram (make-program! bthreads' opts)]
     (when kill-after
       (let [killfn (fn [] (bprogram/kill! bprogram))]
         #?(:clj (-> (Executors/newSingleThreadScheduledExecutor)
                     (.schedule ^Runnable killfn
                                kill-after
                                TimeUnit/MILLISECONDS))
            :cljs (js/setTimeout killfn kill-after))))
     (bprogram/stopped bprogram))))
