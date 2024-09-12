(ns tech.thomascothran.pavlov.bprogram.default-bid-collector-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bprogram.proto :refer [collect]]
            [tech.thomascothran.pavlov.bprogram :as bprogram]))

#_(deftest test-bid-collector-with-one-bid
    (is (= {::bprogram/request #{:x}
            ::bprogram/wait-on #{}
            ::bprogram/block #{}}
           (collect bprogram/default-bid-collector
                    [{:request #{:x}}]
                    {}))))

