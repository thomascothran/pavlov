# Pavlov bthreads + bprograms REPL reference

## Quick require block

```clojure
(require '[clojure.repl :refer [doc source find-doc apropos]])
(require '[tech.thomascothran.pavlov.bthread :as b])
(require '[tech.thomascothran.pavlov.bprogram.ephemeral :as bpe])
(require '[tech.thomascothran.pavlov.bprogram :as bp])
(require '[tech.thomascothran.pavlov.nav :as pnav])
(require '[tech.thomascothran.pavlov.event :as e])
(require '[tech.thomascothran.pavlov.subscribers.tap :as tap])
```

## Bthread constructors

Prefer higher-level constructors before `b/step`:

- `b/bids` for scripted sequences
- `b/on` for single-event reactions
- `b/after-all` for prerequisites
- `b/thread` for branching on event types

Use ordered collections for priority and sets/maps for branching. Use `event/type` instead of assuming events are maps.

## Step a bthread at the REPL

```clojure
(defn make-counter []
  (b/step (fn [state _]
            (if (nil? state)
              [0 {:wait-on #{:tick}}]
              [(inc state) {:wait-on #{:tick}}]))))

(let [bt (make-counter)]
  [(b/notify! bt nil)
   (b/notify! bt {:type :tick})
   (b/state bt)])
```

`b/notify!` is REPL/testing only. Production runs bthreads inside a bprogram.

## Compose and run a bprogram

```clojure
(defn make-bthreads []
  [[:a (b/bids [{:request #{{:type :a}}}])]
   [:b (b/bids [{:wait-on #{:a}}
                {:request #{{:type :done :terminal true}}}])]])

(defn run-once
  []
  @(bpe/execute! (make-bthreads) {:kill-after 1000}))

(defn make-program
  []
  (bpe/make-program! (make-bthreads)))

(let [program (make-program)]
  (bp/submit-event! program {:type :a})
  @(bp/stop! program))
```

## Navigate execution paths with `nav`

```clojure
(defn make-root []
  (pnav/root {:numbers (b/bids [{:request #{1 2}} {:request #{3}}])
              :letters (b/bids [{:request [:a]} {:request [:b]}])}))

(let [root (make-root)]
  {:branches (->> (:pavlov/branches root)
                  (mapv (comp e/type :pavlov/event)))
   :at-1 (some-> (pnav/to root 1) :pavlov/event e/type)
   :at-3 (some-> (pnav/follow root [1 3]) :pavlov/event e/type)})
```

## Inspect bprogram state with the tap subscriber

```clojure
(defn make-program []
  (bpe/make-program!
    (make-bthreads)
    {:subscribers {:tap tap/subscriber}}))

(let [program (make-program)]
  (bp/submit-event! program {:type :a})
  @(bp/stop! program))
```

Tap payloads include `:bthread->bid`, `:waiting-on`, `:requested`, `:blocked`, and `:unblocked`.

## Optional: Portal navigation

If Portal is on the classpath:

```clojure
(require '[portal.api :as portal]
         '[tech.thomascothran.pavlov.viz.portal :as pvp])

(defn navigable
  []
  (pvp/bthreads->navigable (make-bthreads)))

(let [p (portal/open)]
  (add-tap #'portal/submit)
  (tap> (navigable)))
```
