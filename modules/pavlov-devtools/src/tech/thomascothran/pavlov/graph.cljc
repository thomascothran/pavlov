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
     (fn [{:keys [nodes edges id->path]} wrapped]
       (let [identifier (search/identifier nav wrapped)
             node-id (:path wrapped)
             last-event (get-in wrapped [:bprogram/state :last-event])
             id->path (if (contains? id->path identifier)
                        id->path
                        (assoc id->path identifier node-id))
             successors (search/succ nav wrapped)
             nodes (assoc nodes node-id {:path node-id
                                         :identifier identifier
                                         :event last-event
                                         :wrapped wrapped})
             [edges id->path]
             (reduce (fn [[edges id->path] {:keys [state event]}]
                       (let [succ-identifier (search/identifier nav state)
                             succ-path (get id->path succ-identifier (:path state))
                             id->path (if (contains? id->path succ-identifier)
                                        id->path
                                        (assoc id->path succ-identifier succ-path))]
                         [(conj edges {:from node-id
                                       :to succ-path
                                       :event event})
                          id->path]))
                     [edges id->path]
                     successors)]
         {:nodes nodes :edges edges :id->path id->path}))
     {:nodes {} :edges [] :id->path {}})))
