(ns tech.thomascothran.pavlov.search
  (:import [clojure.lang PersistentQueue]))

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
    (step (conj PersistentQueue/EMPTY (root nav)) #{})))

(defn bfs-reduce
  "Breadth-first traversal of NAV.
   f   – (fn [acc state])        ;; combine into an accumulator
   init – initial accumulator
   Returns the final accumulator, or a (reduced …) early-exit value."
  [nav f init]
  (loop [queue (conj PersistentQueue/EMPTY (root nav))
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
