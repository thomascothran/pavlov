(ns tech.thomascothran.bthread-test
  (:require #?(:clj [clojure.test :refer [deftest is testing run-tests]]
               :cljs [cljs.test :refer-macros [deftest is testing run-tests]])
            [tech.thomascothran.pavlov.bthread :as bthread]
            [tech.thomascothran.pavlov.bthread.defaults]))

(deftest test-bid-sequence
  (let [abc [{:name `request-a
              :request #{:a}}
             {:name `request-b
              :request #{:b}}
             {:name `request-c
              :request #{:c}}]
        bthread (bthread/seq abc)]
    (is (= (first abc)
           (bthread/bid bthread {:type :test})))
    (is (= (second abc)
           (bthread/bid bthread {:type :test})))
    (is (= (-> (nth abc 2) bthread/name)
           (-> bthread
               (bthread/bid {:type :test})
               (bthread/name))))
    (is (nil? (bthread/bid bthread {:type :test})))))

(deftest test-bid-reduce
  (testing "Should retain state"
    (let [reducer-fn (fn [{:keys [times-called]} _]
                       (when-not (= times-called 3)
                         {:request #{:more}
                          :times-called (inc times-called)}))
          bthread (bthread/reduce reducer-fn {:times-called 0})]
      (is (= {:request #{:more}
              :times-called 1} (bthread/bid bthread {:type :test})))
      (is (= {:request #{:more}
              :times-called 2} (bthread/bid bthread {:type :test})))
      (is (= {:request #{:more}
              :times-called 3} (bthread/bid bthread {:type :test})))
      (is (= nil (bthread/bid bthread {:type :test}))))))


