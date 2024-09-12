(ns tech.thomascothran.pavlov.bthread.defaults
  (:require [tech.thomascothran.pavlov.bthread.proto :as bthread]))

(extend-protocol bthread/BThread
  #?(:clj clojure.lang.APersistentMap
     :cljs cljs.core.PersistentHashMap)
  (bid [this _event] this))
