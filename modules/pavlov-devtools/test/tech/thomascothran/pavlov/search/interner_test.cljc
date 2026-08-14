(ns tech.thomascothran.pavlov.search.interner-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [tech.thomascothran.pavlov.search.interner :as interner]))

#?(:clj
   (deftype CollidingKey [value]
     Object
     (equals [_ other]
       (and (instance? CollidingKey other)
            (= value (.-value ^CollidingKey other))))
     (hashCode [_] 0)

     clojure.lang.IHashEq
     (hasheq [_] 0))
   :cljs
   (deftype CollidingKey [value]
     IEquiv
     (-equiv [_ other]
       (and (instance? CollidingKey other)
            (= value (.-value other))))

     IHash
     (-hash [_] 0)))

(deftest allocates-dense-identifiers
  (let [initial-interner (interner/make)
        [interner-1 result-1] (interner/intern initial-interner {:state :first})
        [interner-2 result-2] (interner/intern interner-1 {:state :second})
        [_interner-3 result-3] (interner/intern interner-2 {:state :third})]
    (is (= {:id 0 :new? true} result-1))
    (is (= {:id 1 :new? true} result-2))
    (is (= {:id 2 :new? true} result-3))
    (is (= (interner/make) initial-interner)
        "Interning leaves the input value unchanged")))

(deftest reuses-identifiers-for-equal-structural-keys
  (let [first-key {:saved-state [1 2 3]
                   :bid {:request #{:a :b}}}
        equal-key {:bid {:request #{:b :a}}
                   :saved-state [1 2 3]}
        [interner-1 first-result] (interner/intern (interner/make) first-key)
        [interner-2 second-result] (interner/intern interner-1 equal-key)]
    (is (= {:id 0 :new? true} first-result))
    (is (= {:id 0 :new? false} second-result))
    (is (= interner-1 interner-2)
        "Reusing an identifier does not change the interner")))

(deftest hash-collisions-do-not-merge-unequal-keys
  (let [first-key (CollidingKey. :first)
        second-key (CollidingKey. :second)
        [interner-1 first-result] (interner/intern (interner/make) first-key)
        [interner-2 second-result] (interner/intern interner-1 second-key)
        [_interner-3 repeated-result]
        (interner/intern interner-2 (CollidingKey. :first))]
    (is (= (hash first-key) (hash second-key)))
    (is (not= first-key second-key))
    (is (= {:id 0 :new? true} first-result))
    (is (= {:id 1 :new? true} second-result))
    (is (= {:id 0 :new? false} repeated-result))))

(deftest interners-have-independent-id-domains
  (let [[first-interner first-result]
        (interner/intern (interner/make) :a)
        [_first-interner second-result]
        (interner/intern first-interner :b)
        [_second-interner independent-result]
        (interner/intern (interner/make) :b)]
    (is (= {:id 0 :new? true} first-result))
    (is (= {:id 1 :new? true} second-result))
    (is (= {:id 0 :new? true} independent-result))))
