(ns demo.portal
  (:require [tech.thomascothran.pavlov.bthread :as b]
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
        (nav/root bthreads))

      (tap> root-node)))
