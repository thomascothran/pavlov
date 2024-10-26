(ns tech.thomascothran.pavlov.bthread.defaults
  (:require [tech.thomascothran.pavlov.bthread.proto :as bthread]))

#?(:clj (extend-protocol bthread/BThread
          clojure.lang.APersistentMap
          (bid
            ([this] this)
            ([this _event] this))
          (priority [this] (get this :priority 0)))
   :cljs (extend-protocol bthread/BThread

           cljs.core.PersistentArrayMap
           (bid
             ([this] this)
             ([this _event] this))
           (priority [this] (get this :priority 0))

           cljs.core.PersistentHashMap
           (bid
             ([this] this)
             ([this _event] this))
           (priority [this] (get this :priority 0))))

(extend-protocol bthread/BThread
  nil
  (bid [this _event] this)
  (priority [_] 0))
