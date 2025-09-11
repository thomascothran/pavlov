(ns ^:alpha tech.thomascothran.pavlov.nav
  (:refer-clojure :exclude [ancestors])
  (:require [clojure.core.protocols :as p]
            [tech.thomascothran.pavlov.search :as search]))

(defn- node->data
  [nav wrapped chosen ancestors]
  (let [succs    (search/succ nav wrapped)
        make-child (fn [state event]
                     {:nav nav :wrapped state :chosen event
                      :ancestors (conj ancestors {:nav nav :wrapped wrapped
                                                  :chosen chosen :ancestors ancestors})})
        branches (-> (mapv (fn [{:keys [state event]}]
                             {:pavlov/event  event
                              :pavlov/path   (:path state)
                              ::child        (make-child state event)})
                           succs)
                     (with-meta
                       {`p/nav
                        (fn [_coll _k v]
                          (if-let [{:keys [nav wrapped chosen ancestors]} (::child v)]
                            (node->data nav wrapped chosen ancestors)
                            v))}))
        crumbs  (-> (mapv (fn [{:keys [wrapped chosen] :as n}]
                            {:pavlov/event chosen
                             :pavlov/path  (:path wrapped)
                             ::child       n})
                          ancestors)
                    (vec)
                    (with-meta
                      {`p/nav
                       (fn [_coll _k v]
                         (let [{:keys [nav wrapped chosen ancestors]} (::child v)]
                           (node->data nav wrapped chosen ancestors)))}))]
    {:pavlov/event    chosen
     :pavlov/path     (:path wrapped)
     :pavlov/branches branches
     :pavlov/crumbs   crumbs
     :pavlov/bthreads
     {:pavlov/bthread-states (:saved-bthread-states wrapped)
      :pavlov/bthread->bid (get-in wrapped [:bprogram/state
                                            :bthread->bid])
      :pavlov/bthreads-by-priority
      (get-in wrapped [:bprogram/state
                       :bthreads-by-priority])}}))

(defn root
  "Given a navigator from `search` return a navigable data structure."
  [bthreads]
  (let [nav (search/make-navigator bthreads)]
    (node->data nav (search/root nav) nil [])))
