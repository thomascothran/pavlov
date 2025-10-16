(ns ^:alpha tech.thomascothran.pavlov.nav
  "Tools to navigate bthread programs.

  Convert a group of bthreads to a navigable program with `root`.
  Then use `to` and `follow` to navigate around the branching
  execution paths."
  (:refer-clojure :exclude [ancestors])
  (:require [clojure.core.protocols :as p]
            [clojure.datafy :refer [nav]]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.search :as search]))

(defn- node->data
  [nav wrapped chosen ancestors tf]
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
                            (node->data nav wrapped chosen ancestors tf)
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
                           (node->data nav wrapped chosen ancestors tf)))}))]
    (tf
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
                        :bthreads-by-priority])}})))

(defn root
  "Given a navigator from `search` return a navigable data structure."
  ([bthreads] (root bthreads identity))
  ([bthreads tf]
   (let [nav (search/make-navigator bthreads)]
     (node->data nav (search/root nav) nil [] tf))))

(defn to
  "Given a navigable, navigate to the path that matches the given
  `event-selector`.

  If the `event-selector` is a function, it will be used as
  a predicate to find which branch to take.

  Otherwise, the `event-selector` is an event type. In this case
  there may be more than one branch with the same event type.
  In that case, the first one is returned."
  [navigable event-selector]
  (let [branches (:pavlov/branches navigable)
        select-branch-fn (if (fn? event-selector)
                           event-selector
                           (comp (partial = event-selector)
                                 e/type))
        branch
        (first (filter (comp select-branch-fn
                             #(get % :pavlov/event))
                       branches))]
    (when branch
      (nav branches nil branch))))

(defn follow
  "Given a navigable, follow the path of event selectors.

  Event selectors only need to be specified where there are branches.

  If a there is only one branch to follow, it is followed automatically,
  even if it is not in the selectors list.

  If there are multiple options at a branch point, but none of them
  are in the event-types list, then nil is returned.

  Params
  ------
  `navigable`: the product of calling `root` on a group of bthreads
  `event-selectors`: a sequence of either:
    - event types, or
    - predicate functions that return a truthy given an event if that
      branch ought to be followed"
  [navigable event-selectors]
  (loop [nav-position navigable
         remaining-event-types event-selectors]
    (let [event-type (first remaining-event-types)

          next-event-type
          (or (and (= 1 (count (:pavlov/branches nav-position)))
                   (-> nav-position :pavlov/branches first
                       :pavlov/event e/type))
              event-type)

          next-position (to nav-position next-event-type)]

      (if (and nav-position (seq remaining-event-types))
        (recur
         next-position
         (if (= next-event-type event-type)
           (rest remaining-event-types)
           remaining-event-types))
        nav-position))))
