(ns tech.thomascothran.pavlov.bprogram.alpha-test
  (:require [clojure.test :refer [deftest testing is]]
            [tech.thomascothran.pavlov.bthread :as bthread]
            [tech.thomascothran.pavlov.defaults]
            [tech.thomascothran.pavlov.bprogram.proto :as bp]
            [tech.thomascothran.pavlov.bprogram.alpha :as bpa]))

(deftest good-morning-and-evening
  (let [bthreads
        [(bthread/seq (repeat 4 {:request #{:good-morning}})
                      {:priority 1})

         (bthread/seq (repeat 4 {:request #{:good-evening}}))
         (bthread/seq (interleave
                       (repeat {:wait-on #{:good-morning}
                                :block #{:good-evening}})
                       (repeat {:wait-on #{:good-evening}
                                :block #{:good-morning}})))]
        program   (bpa/make-program! bthreads)
        out-queue (:out-queue program)
        stop-p @(bp/stop! program)]
    (is (= (interleave (repeat 4 :good-morning)
                       (repeat 4 :good-evening))
           (seq out-queue)))))

