(ns tech.thomascothran.pavlov.bprogram.internal-squint-test
  (:require ["vitest" :refer [expect test]]
            [tech.thomascothran.pavlov.bthread :as bthread]
            [tech.thomascothran.pavlov.defaults]
            [tech.thomascothran.pavlov.bprogram.proto :as bp]
            [tech.thomascothran.pavlov.bprogram.internal :as bpi]))

(test "dummy expect works"
      (fn []
        (-> (expect 1)
            (.toBe 1))))

;; Stopped here due to extend-protocol not being supported in squint
#_(test "good-morning-and-evening"
        (fn []
          (let [bthreads
                [(bthread/seq (repeat 4 {:request #{:good-morning}})
                              {:priority 1}

                              (bthread/seq (repeat 4 {:request #{:good-evening}}))
                              (bthread/seq (interleave
                                            (repeat {:wait-on #{:good-morning}
                                                     :block #{:good-evening}})
                                            (repeat {:wait-on #{:good-evening}
                                                     :block #{:good-morning}}))))]
                !a        (atom [])
                subscriber  (fn [x _] (swap! !a conj x))
                program   (bpi/make-program! bthreads
                                             {:subscribers {:test subscriber}})
                _         @(bp/stop! program)]
            (-> (expect (butlast @!a))
                (.toEqual
                 (interleave (repeat 4 :good-morning
                                     (repeat 4 :good-evening))
                             (butlast @!a)))))))
