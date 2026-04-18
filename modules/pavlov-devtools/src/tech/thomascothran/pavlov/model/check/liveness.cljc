(ns tech.thomascothran.pavlov.model.check.liveness
  (:require [clojure.set :as set]
            [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.graph.algo :as algo]))

(defn hot?
  [node]
  (boolean
   (some bid/hot
         (vals (:bthread->bid node)))))

(defn hot-node-ids
  [lts]
  (into #{}
        (comp (filter (comp hot? second))
              (map first))
        (:nodes lts)))

(defn leaf-node-ids
  [lts]
  (let [all-node-ids (into #{} (keys (:nodes lts)))
        nodes-with-outgoing (into #{} (map :from) (:edges lts))]
    (set/difference all-node-ids nodes-with-outgoing)))

(defn terminal-target-node-ids
  [lts]
  (into #{}
        (comp (filter (comp e/terminal? :event))
              (map :to))
        (:edges lts)))

(defn deadlock-node-ids
  [lts]
  (set/difference (leaf-node-ids lts)
                  (terminal-target-node-ids lts)))

(defn path-to-node
  ([lts node-id]
   (path-to-node lts (algo/lts->outgoing-index lts) node-id))
  ([lts outgoing-index node-id]
   (let [succ-fn (fn [curr-node-id]
                   (algo/succ outgoing-index
                              (constantly true)
                              curr-node-id))]
     (algo/find-path (:root lts) succ-fn #(= % node-id)))))

(defn- witness-path-to-node
  [lts outgoing-index node-id]
  (if (= (:root lts) node-id)
    []
    (path-to-node lts outgoing-index node-id)))

(defn- natural-node-order
  [lts]
  (let [{:keys [seen order]}
        (reduce (fn [{:keys [seen order]} {:keys [from to]}]
                  (let [[seen order]
                        (if (contains? seen from)
                          [seen order]
                          [(conj seen from) (conj order from)])
                        [seen order]
                        (if (contains? seen to)
                          [seen order]
                          [(conj seen to) (conj order to)])]
                    {:seen seen
                     :order order}))
                {:seen (cond-> #{}
                         (:root lts) (conj (:root lts)))
                 :order (cond-> []
                          (:root lts) (conj (:root lts)))}
                (:edges lts))]
    (into order
          (remove seen)
          (keys (:nodes lts)))))

(defn- hot-terminal-violation
  [lts outgoing-index]
  (when-let [edge (some (fn [edge]
                          (when (and (e/terminal? (:event edge))
                                     (hot? (get-in lts [:nodes (:to edge)])))
                            edge))
                        (:edges lts))]
    (when-let [prefix-path (witness-path-to-node lts outgoing-index (:from edge))]
      {:node-id (:to edge)
       :path-edges (conj prefix-path edge)
       :state (get-in lts [:nodes (:to edge)])})))

(defn- hot-deadlock-violation
  [lts outgoing-index]
  (let [deadlock-node-ids (deadlock-node-ids lts)]
    (when-let [{:keys [node-id path-edges]}
               (some (fn [node-id]
                       (when (and (contains? deadlock-node-ids node-id)
                                  (hot? (get-in lts [:nodes node-id])))
                         (when-let [path-edges (witness-path-to-node lts outgoing-index node-id)]
                           {:node-id node-id
                            :path-edges path-edges})))
                     (natural-node-order lts))]
      {:node-id node-id
       :path-edges path-edges
       :state (get-in lts [:nodes node-id])})))

(defn- hot-cycle
  [lts outgoing-index]
  (let [hot-node-ids (hot-node-ids lts)
        hot-node-id? (fn [node-id]
                       (contains? hot-node-ids node-id))
        hot-succ (fn [node-id]
                   (algo/succ outgoing-index
                              (comp hot-node-id? :to)
                              node-id))
        start-node-ids (into []
                             (filter hot-node-id?)
                             (natural-node-order lts))]
    (loop [state {:start-node-ids start-node-ids
                  :gray #{}
                  :black #{}
                  :stack []
                  :edge-stack []
                  :node-id->edge-index {}}]
      (let [{:keys [start-node-ids gray black stack edge-stack node-id->edge-index]}
            state
            next-step
            (cond
              (seq stack)
              (let [{:keys [node-id remaining-edges]} (peek stack)]
                (if-let [edge (first remaining-edges)]
                  (let [successor-id (:to edge)
                        stack' (conj (pop stack)
                                     {:node-id node-id
                                      :remaining-edges (next remaining-edges)})
                        edge-stack' (conj edge-stack edge)]
                    (cond
                      (contains? gray successor-id)
                      {:result {:entry-node-id successor-id
                                :cycle-edges (subvec edge-stack'
                                                     (get node-id->edge-index successor-id))}}

                      (contains? black successor-id)
                      {:state (assoc state :stack stack')}

                      :else
                      {:state (assoc state
                                     :gray (conj gray successor-id)
                                     :stack (conj stack'
                                                  {:node-id successor-id
                                                   :remaining-edges (seq (hot-succ successor-id))})
                                     :edge-stack edge-stack'
                                     :node-id->edge-index (assoc node-id->edge-index
                                                                 successor-id
                                                                 (count edge-stack')))}))
                  {:state (assoc state
                                 :gray (disj gray node-id)
                                 :black (conj black node-id)
                                 :stack (pop stack)
                                 :edge-stack (if (> (count stack) 1)
                                               (pop edge-stack)
                                               edge-stack)
                                 :node-id->edge-index (dissoc node-id->edge-index node-id))}))

              (seq start-node-ids)
              (let [start-node-id (first start-node-ids)
                    remaining-start-node-ids (rest start-node-ids)]
                (if (or (contains? gray start-node-id)
                        (contains? black start-node-id))
                  {:state (assoc state :start-node-ids remaining-start-node-ids)}
                  {:state (assoc state
                                 :start-node-ids remaining-start-node-ids
                                 :gray (conj gray start-node-id)
                                 :stack [{:node-id start-node-id
                                          :remaining-edges (seq (hot-succ start-node-id))}]
                                 :edge-stack []
                                 :node-id->edge-index {start-node-id 0})}))

              :else
              {:result nil})]
        (if (contains? next-step :result)
          (:result next-step)
          (recur (:state next-step)))))))

(defn liveness-violation
  ([lts]
   (liveness-violation lts (algo/lts->outgoing-index lts)))
  ([lts outgoing-index]
   (when-not (:truncated lts)
     (or (hot-terminal-violation lts outgoing-index)
         (hot-deadlock-violation lts outgoing-index)
         (when-let [{:keys [entry-node-id cycle-edges]} (hot-cycle lts outgoing-index)]
           {:node-id entry-node-id
            :path-edges (witness-path-to-node lts outgoing-index entry-node-id)
            :cycle-edges cycle-edges
            :state (get-in lts [:nodes entry-node-id])})))))
