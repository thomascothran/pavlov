(ns tech.thomascothran.pavlov.viz.portal
  (:refer-clojure :exclude [ancestors])
  (:require [clojure.core.protocols :as p]
            [portal.api :as portal]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.search :as search]))

(defn hiccup-viewer
  [x]
  (vary-meta x assoc :portal.viewer/default :portal.viewer/hiccup))

(defn- node->data
  [nav wrapped chosen ancestors]
  (let [succs    (search/succ nav wrapped)
        bthread-states (:saved-bthread-states wrapped)
        bthread->bid (get-in wrapped [:bprogram/state
                                      :bthread->bid])
        make-child (fn [state event]
                     {:nav nav :wrapped state :chosen event
                      :ancestors (conj ancestors {:nav nav :wrapped wrapped
                                                  :chosen chosen :ancestors ancestors})})
        branches (-> (mapv (fn [{:keys [state event]}]
                             (-> (if (map? event)
                                   event
                                   {:type event})
                                 (vary-meta assoc ::child
                                            (make-child state event))))
                           succs)
                     (with-meta
                       {`p/nav
                        (fn [_coll _k v]
                          (if-let [{:keys [nav wrapped chosen ancestors]}
                                   (::child (meta v))]
                            (node->data nav wrapped chosen ancestors)
                            v))}))
        crumbs  (->> (mapv (fn [{:keys [chosen]}]
                             (e/type chosen))
                           ancestors)
                     (filterv identity))]
    (filterv
     identity
     [(hiccup-viewer
       [:h1 "Bthread Navigator"])
      (hiccup-viewer
       [:p "Navigate around the bprogram by double clicking the events under branches"])
      (when chosen
        (hiccup-viewer
         [:h2  (str "Event: " (e/type chosen))]))
      (hiccup-viewer
       [:h3 "Branches"])
      branches
      (hiccup-viewer [:hr])
      (hiccup-viewer
       [:h3 "History"])
      crumbs])))

(defn bthreads->navigable
  "Given a navigator from `search` return a navigable data structure."
  [bthreads]
  (let [nav (search/make-navigator bthreads)]
    (node->data nav (search/root nav) nil [])))

(comment
  ;; setup
  (require '[tech.thomascothran.pavlov.bthread :as b])
  (do (def p (portal/open))
      (add-tap #'portal/submit))

  (do (def bthreads {:letters (b/bids [{:request [:a]}
                                       {:request [:b]}
                                       {:request [:c]}])
                     :numbers (b/bids [{:request #{1 2}}
                                       {:request #{3}}])})
      (tap>
       (bthreads->navigable bthreads))))
