(ns demo.portal
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.nav :as nav]
            [portal.api :as p]))

(comment
  ;; setup
  (do (def p (p/open))
      (add-tap #'p/submit)))

(comment
  (do (def bthreads {:letters (b/bids [{:request [:a]}
                                       {:request [:b]}
                                       {:request [:c]}])
                     :numbers (b/bids [{:request #{1 2}}
                                       {:request #{3}}])})
      (def root-node
        (nav/root bthreads
                  (fn [{event :pavlov/event
                        branches :pavlov/branches}]
                    [^{:portal.viewer/default :portal.viewer/hiccup}
                     [:div
                      (if-not event
                        [:h1 "Initialized"]
                        [:h1 "Event: " (e/type event)])]
                     ^{:portal.viewer/default :portal.viewer/hiccup}
                     [:h3 "Branches"]
                     branches
                     ^{:portal.viewer/default :portal.viewer/hiccup}
                     [:hr]])))

      (tap> root-node)
      (:wrapped root-node)
      (get-in root-node [:pavlov/bthreads])))
