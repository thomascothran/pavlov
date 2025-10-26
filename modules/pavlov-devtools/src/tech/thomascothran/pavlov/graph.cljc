(ns tech.thomascothran.pavlov.graph
  "Graph utilities for Pavlov behavioral programs."
  (:require [tech.thomascothran.pavlov.search :as search]))

(defn ->graph
  "Return a graph representation for the supplied bthreads.

  The returned structure will be map-based, with nodes and edges keyed in
  a way suitable for visualization and analysis tooling.

  Example:
  ```clojure
  (->graph {:go-to-work go-to-work-bthread
            :start-work start-work-bthread})
  ;;=> {:nodes {...}
  ;;    :edges {...}}
  ```
  "
  [bthreads]
  (let [nav (search/make-navigator bthreads)]
    (search/bfs-reduce
     nav
     (fn [acc wrapped]
       (let [nodes (get acc :nodes)
             edges (get acc :edges)
             identifier (search/identifier nav wrapped)
             node-id (get wrapped :path)
             last-event (get-in wrapped [:bprogram/state :last-event])
             successors (search/succ nav wrapped)
             nodes' (assoc nodes node-id {:path node-id
                                          :identifier identifier
                                          :event last-event
                                          :wrapped wrapped})
             edges' (into edges
                          (map (fn [m]
                                 {:from node-id
                                  :to (get-in m [:state :path])
                                  :event (get m :event)}))
                          successors)]
         (assoc acc :nodes nodes' :edges edges')))
     {:nodes {} :edges []})))
