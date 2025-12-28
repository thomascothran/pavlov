(ns ^:alpha tech.thomascothran.pavlov.viz.cytoscape
  (:require [clojure.string :as str]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.graph :as graph]))

(defn- path->id
  [path]
  (format "node-%08x" (bit-and (hash path) 0xffffffff)))

(defn- label-for
  [path event]
  (cond
    (empty? path) "initialize"
    (some? event) (-> (or (e/type event) event) pr-str)
    :else (-> (last path) pr-str)))

(def ^:private state-keys
  [:last-event :next-event :requests :waits :blocks :bthread->bid :bthreads-by-priority])

(def ^:private default-state
  {:last-event nil
   :next-event nil
   :requests {}
   :waits {}
   :blocks {}
   :bthread->bid {}
   :bthreads-by-priority #{}})

(defn- sanitize-state
  [state]
  (when (map? state)
    (merge default-state (select-keys state state-keys))))

(defn- wrapped->meta
  [wrapped]
  (let [wrapped-map (when (map? wrapped) wrapped)]
    {:saved-bthread-states (or (get wrapped-map :saved-bthread-states) {})
     :bprogram/state (or (some-> wrapped-map :bprogram/state sanitize-state)
                         default-state)}))

(defn- node-meta
  [path identifier event wrapped]
  (let [{saved-bthread-states :saved-bthread-states
         state :bprogram/state} (wrapped->meta wrapped)]
    {:path path
     :identifier identifier
     :event event
     :saved-bthread-states saved-bthread-states
     :bprogram/state state}))

(defn- event-flags
  [event]
  (let [flag? (fn [k]
                (boolean (and (map? event)
                              (get event k))))]
    {:environment? (flag? :environment)
     :terminal? (flag? :terminal)
     :invariant? (flag? :invariant-violated)}))

(defn- node->cy-data
  [[path {:keys [identifier event wrapped]}]]
  (let [meta (node-meta path identifier event wrapped)
        flags (event-flags event)
        classes (->> [(when (:environment? flags) "environment")
                      (when (:terminal? flags) "terminal")
                      (when (:invariant? flags) "invariant")]
                     (remove nil?)
                     (str/join " "))]
    (cond-> {:data {:id (path->id path)
                    :label (label-for path event)
                    :path path
                    :identifier identifier
                    :event event
                    :meta meta
                    :flags flags}}
      (seq classes) (assoc :classes classes))))

(defn- edge->cy-data
  [{:keys [from to] :as edge}]
  {:data (assoc edge
                :id (str (path->id from) "->" (path->id to))
                :source (path->id from)
                :target (path->id to))})

(defn -graph->cytoscape
  "Given a graph from `tech.thomascothran.pavlov.graph/->graph`, build Cytoscape elements.

  Example:
  (-graph->cytoscape {:nodes {...} :edges [...]})
  ;; => {:nodes [...]
  ;;     :edges [...]}"
  [graph]
  {:nodes (->> graph
               :nodes
               (sort-by (juxt (comp count key) (comp pr-str key)))
               (map node->cy-data)
               vec)
   :edges (->> graph :edges (map edge->cy-data) vec)})

(defn graph->cytoscape
  "Return Cytoscape-compatible graph data for the supplied bthreads map.

  Example:
  (graph->cytoscape {:foo bthread})
  ;; => {:nodes [...]
  ;;     :edges [...]}"
  [bthreads]
  (-> bthreads
      graph/->graph
      -graph->cytoscape))

(defn lts->cytoscape
  "Convert an LTS (Labeled Transition System) to Cytoscape format.
  
  Takes an LTS map with keys:
  - :root - the root state identifier
  - :nodes - map of state-id to state-data
  - :edges - vector of edge maps with :from, :to, :event
  - :truncated - boolean indicating if the LTS was truncated
  
  Returns a map with:
  - :nodes - vector of Cytoscape node elements
  - :edges - vector of Cytoscape edge elements"
  [{:keys [root nodes edges]}]
  ;; Compute sets for node classification
  (let [nodes-with-outgoing (into #{} (map :from) edges)
        terminal-nodes (into #{}
                             (comp (filter #(-> % :event :terminal))
                                   (map :to))
                             edges)
        nodes-with-incoming (into #{} (map :to) edges)]
    {:nodes (vec (for [[node-id node-data] nodes]
                   (let [has-outgoing? (contains? nodes-with-outgoing node-id)
                         is-terminal? (contains? terminal-nodes node-id)
                         is-root? (= node-id root)
                         has-incoming? (contains? nodes-with-incoming node-id)
                         is-deadlock? (and (not has-outgoing?)
                                           (not is-terminal?)
                                           (not is-root?)
                                           has-incoming?)]
                     (cond-> {:data {:id (pr-str node-id)
                                     :label ""
                                     :meta node-data}}
                       ;; Priority order: root > terminal > deadlock
                       ;; Root takes highest precedence
                       is-root?
                       (assoc :classes "root")

                       ;; Terminal: has incoming terminal edge AND no outgoing edges
                       (and (not is-root?)
                            is-terminal?
                            (not has-outgoing?))
                       (assoc :classes "terminal")

                       ;; Deadlock: has incoming edge, no outgoing, not terminal, not root
                       (and (not is-root?)
                            (not is-terminal?)
                            is-deadlock?)
                       (assoc :classes "deadlock")))))
     :edges (vec (for [{:keys [from to event]} edges]
                   {:data {:id (str "edge-" (pr-str from) "->" (pr-str to))
                           :source (pr-str from)
                           :target (pr-str to)
                           :label (str (:type event))
                           :event event}}))}))
