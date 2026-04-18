(ns tech.thomascothran.pavlov.search
  (:refer-clojure :exclude [ancestors])
  (:require [tech.thomascothran.pavlov.event.selection :as selection]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram.state :as state]
            [tech.thomascothran.pavlov.bid.proto :as bid]
            [hasch.core :as hasch])
  #?(:clj (:import [clojure.lang PersistentQueue])))

;; helper functions

(defn- save-bthread-states
  "Save the current state of all bthreads."
  [bp-state]
  (let [name->bthread (:name->bthread bp-state)]
    (into {}
          (map (fn [[name bthread]]
                 (let [bt-state (b/state bthread)]
                   [name bt-state])))
          name->bthread)))

(defn- restore-bthread-states
  "Restore bthread states from a saved snapshot."
  [bp-state saved-states]
  (let [name->bthread (:name->bthread bp-state)]
    (doseq [[name bthread] name->bthread]
      (when (contains? saved-states name)
        (b/set-state bthread (get saved-states name))))
    bp-state))

(defprotocol StateNavigator
  (root [_] "Returns an initial value")
  (succ [_ state] "returns (seq {:state s' :event e})")
  (identifier [_ state] "hash/keyword for caching"))

(defn bfs-seq [nav]
  (letfn [(step [frontier seen]
            (lazy-seq
             (when-let [s (peek frontier)]
               (let [frontier' (pop frontier)
                     sid (identifier nav s)]
                 (if (contains? seen sid)
                   (step frontier' seen)
                   (cons s
                         (step (into frontier' (map :state (succ nav s)))
                               (conj seen sid))))))))]
    (step (conj #?(:clj PersistentQueue/EMPTY
                   :cljs cljs.core/PersistentQueue.EMPTY)
                (root nav)) #{})))

(defn bfs-reduce
  "Breadth-first traversal of NAV.
   f   – (fn [acc state])        ;; combine into an accumulator
   init – initial accumulator
   Returns the final accumulator, or a (reduced …) early-exit value."
  [nav f init]
  (loop [queue (conj #?(:clj PersistentQueue/EMPTY
                        :cljs cljs.core/PersistentQueue.EMPTY)
                     (root nav))
         seen #{}
         acc init]
    (if (seq queue)
      (let [s (peek queue)
            queue (pop queue)
            sid (identifier nav s)]
        (if (contains? seen sid) ; duplicate, skip
          (recur queue seen acc)
          (let [acc' (f acc s)]
            (if (reduced? acc') ; found error → stop
              @acc'
              (recur (reduce conj queue (map :state (succ nav s)))
                     (conj seen sid)
                     acc')))))
      acc))) ; frontier empty → done

(defn dfs-seq [nav]
  (letfn [(step [stack seen]
            (lazy-seq
             (when-let [[s & rest] (seq stack)]
               (let [sid (identifier nav s)]
                 (if (contains? seen sid)
                   (step rest seen)
                   (cons s
                         (step (into (mapv :state (succ nav s)) rest)
                               (conj seen sid))))))))]
    (step [(root nav)] #{})))

(defn dfs-reduce
  "Depth-first traversal of NAV.
     f  – (fn [acc state])      ;; combine into an accumulator
     init – initial accumulator
   Returns  (f ... (f init s0) ... sN)  or a reduced value if
   `f` calls (reduced …) (e.g. when we detect a violation)."
  [nav f init]
  (loop [stack [(root nav)]
         seen #{}
         acc init]
    (if (empty? stack)
      acc
      (let [s (peek stack)
            stack (pop stack)
            sid (identifier nav s)]
        (if (seen sid) ; already explored
          (recur stack seen acc) ; skip
          (let [acc' (f acc s)]
            (if (reduced? acc') ; early exit
              @acc'
              ;; push children lazily, **one at a time**
              (recur (reduce conj stack (map :state (succ nav s)))
                     (conj seen sid)
                     acc')))))))) ; finished

(defn- bids->hashable-bthread-bid
  "bthreads are not hashable. But we need to hash bids.

  Remove the bthread itself and keep the bthread names"
  [bids]
  (update-vals bids
               (fn [bid]
                 (if-let [bthreads (bid/bthreads bid)]
                   (assoc bid :bthreads (keys bthreads))
                   bid))))

(defn- compute-successor-templates
  "Compute path-independent successor templates for a wrapped state.

  Templates cache the event and next scheduler snapshot, but not the caller's
  path, so the same semantic state can be revisited from multiple paths without
  corrupting navigation history."
  [wrapped]
  (let [saved-bthread-states (:saved-bthread-states wrapped)
        state (:bprogram/state wrapped)
        bthread->bid (get state :bthread->bid)
        bthreads-by-priority (get state :bthreads-by-priority)
        branches (selection/prioritized-events bthreads-by-priority
                                               bthread->bid)]
    (mapv (fn [event]
            (restore-bthread-states state saved-bthread-states)
            (let [next-state (state/step state event)]
              {:event event
               :next-state next-state
               :next-saved-bthread-states (save-bthread-states next-state)}))
          branches)))

(defn- materialize-successors
  "Attach the caller's path to cached successor templates."
  [path successor-templates]
  (mapv (fn [successor-template]
          (let [event (:event successor-template)
                next-state (:next-state successor-template)
                next-saved-bthread-states (:next-saved-bthread-states successor-template)]
            {:state {:bprogram/state next-state
                     :path (conj path event)
                     :saved-bthread-states next-saved-bthread-states}
             :event event}))
        successor-templates))

(defn make-navigator
  "Create a StateNavigator for the behavioral program."
  [all-bthreads]
  ;; Initialize the state first, which advances bthreads
  (let [initial-state (state/init all-bthreads)
        ;; Save bthread states AFTER init has advanced them
        saved-initial-states (save-bthread-states initial-state)
        successor-cache (atom {})]
    (reify StateNavigator
      (root [_]
        ;; Wrap state with path tracking and saved bthread states
        {:bprogram/state initial-state
         :path []
         :saved-bthread-states saved-initial-states})

      (succ [this wrapped]
        (let [path (:path wrapped)
              sid (identifier this wrapped)
              successor-templates (or (get @successor-cache sid)
                                      (let [templates (compute-successor-templates wrapped)]
                                        (swap! successor-cache assoc sid templates)
                                        templates))]
          (materialize-successors path successor-templates)))

      (identifier [_ wrapped]
        ;; Use saved states instead of live bthread states to avoid mutation issues
        (let [saved-states (get wrapped :saved-bthread-states)
              bthread->bid (-> (get-in wrapped
                                       [:bprogram/state :bthread->bid])
                               bids->hashable-bthread-bid)
              last-event-terminal (get-in wrapped [:bprogram/state :last-event :terminal])]
          (hasch/uuid [last-event-terminal saved-states bthread->bid]))))))
