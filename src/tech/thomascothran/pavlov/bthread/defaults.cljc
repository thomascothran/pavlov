(ns tech.thomascothran.pavlov.bthread.defaults
  (:require [tech.thomascothran.pavlov.bthread.proto :as bthread]))

(extend-protocol bthread/BThread
  #?(:clj clojure.lang.APersistentMap
     :cljs cljs.core.PersistentHashMap)
  (bid
    ([this] this)
    ([this _event] this))
  (priority [this] (get this :priority 0)))

(extend-protocol bthread/BThread
  nil
  (bid [this _event] this)
  (priority [_] 0))
