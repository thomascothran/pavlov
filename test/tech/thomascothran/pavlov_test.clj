(ns tech.thomascothran.pavlov-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread.proto :as bthread]
            [tech.thomascothran.pavlov.bthread.defaults]
            [tech.thomascothran.pavlov.bid.defaults]
            [tech.thomascothran.pavlov :as bprogram]))

(def x-request
  {:request #{:x}})

(def block-x
  {:block #{:x}})

(def y-request
  (with-meta {:name :y-request}
    {`bthread/bid (constantly {:request #{:y}})}))

(def nil-request
  {})

(def x-waiting-on-y
  {:wait-on #{:y}
   :request #{:x}})

