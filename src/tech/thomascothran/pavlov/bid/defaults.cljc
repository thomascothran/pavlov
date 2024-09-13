(ns tech.thomascothran.pavlov.bid.defaults
  (:require [tech.thomascothran.pavlov.bid.proto :as proto]))

(extend-protocol proto/Bid
  #?(:clj clojure.lang.PersistentVector
     :cljs PersistentVector)
  (request [this] (first this))
  (wait-on [this] (second this))
  (wait [this] (second this))
  (block [this] (when (<= 3 (count this)) (nth this 2))))

(extend-protocol proto/Bid
  #?(:clj clojure.lang.APersistentMap
     :cljs cljs.core.PersistentHashMap)
  (request [this] (get this :request #{}))
  (wait-on [this] (get this :wait-on #{}))
  (block [this] (get this :block #{})))

(extend-protocol proto/Bid
  nil
  (request [_])
  (wait-on [_])
  (block [_]))

