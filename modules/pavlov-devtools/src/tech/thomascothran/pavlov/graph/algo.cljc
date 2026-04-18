(ns tech.thomascothran.pavlov.graph.algo
  (:import #?(:clj [clojure.lang PersistentQueue])))

(defn distinct-by
  "Returns a stateful transducer that removes elements by calling f on each step as a uniqueness key.
   Returns a lazy sequence when provided with a collection.

  Source: https://gist.github.com/thenonameguy/714b4a4aa5dacc204af60ca0cb15db43"
  ([f]
   (fn [rf]
     (let [seen (volatile! #{})]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (let [v (f input)]
            (if (contains? @seen v)
              result
              (do (vswap! seen conj v)
                  (rf result input)))))))))
  ([f xs]
   (sequence (distinct-by f) xs)))

(defn lts->outgoing-index
  [lts]
  (group-by :from (get lts :edges)))

(defn succ
  [outgoing-index edge-predicate node-id]
  (some->> (get outgoing-index node-id)
           (filter edge-predicate)))

(defn- ->frontier-state
  [path edge]
  {:node-id (get edge :to)
   :path (conj path edge)})

(defn find-path
  "Find a path from the starting node to a node whose id satisfies
  `target-node-id-pred`."
  [start-id succ-fn target-node-id-pred]
  (loop [seen #{start-id}
         frontier (conj #?(:clj PersistentQueue/EMPTY
                           :cljs #queue [])
                        {:node-id start-id
                         :path []})]
    (when-let [frontier-state (peek frontier)]
      (let [node-id (get frontier-state :node-id)
            path (get frontier-state :path)]
        (if (target-node-id-pred node-id)
          path
          (let [successors
                (into []
                      (comp (map (partial ->frontier-state path))
                            (remove (comp seen :node-id))
                            (distinct-by :node-id))
                      (succ-fn node-id))]
            (recur (into seen (map :node-id) successors)
                   (into (pop frontier) successors))))))))

(comment
  *e
  (do (require '[tech.thomascothran.pavlov.graph :as g])
      (require '[tech.thomascothran.pavlov.bthread :as b])
      (defn make-bthreads
        []
        {:a (b/bids [{:request #{:a}}])
         :b (b/bids [{:wait-on #{:a}}
                     {:request #{:b}}])
         :c (b/bids [{:wait-on #{:b}}
                     {:request #{:c}}])})
      (def lts
        (g/->lts (make-bthreads)))
      (lts->outgoing-index lts)
      (succ (lts->outgoing-index lts)
            (constantly true)
            (get lts :root))))
