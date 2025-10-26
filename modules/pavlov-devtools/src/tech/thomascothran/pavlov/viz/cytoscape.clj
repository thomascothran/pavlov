(ns ^:alpha tech.thomascothran.pavlov.viz.cytoscape
  (:require [tech.thomascothran.pavlov.graph :as graph]))

(defn- path->id
  [path]
  (pr-str path))

(defn- label-for
  [path]
  (if (empty? path)
    "initialize"
    (pr-str (last path))))

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

(defn- node->cy-data
  [[path {:keys [identifier event wrapped]}]]
  {:data {:id (path->id path)
          :label (label-for path)
          :path path
          :identifier identifier
          :event event
          :meta (node-meta path identifier event wrapped)}})

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
  {:nodes (->> graph :nodes (sort-by key) (map node->cy-data) vec)
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
