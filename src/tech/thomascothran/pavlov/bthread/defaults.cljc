(ns tech.thomascothran.pavlov.bthread.defaults
  (:require [tech.thomascothran.pavlov.bthread.proto :as bthread]))

#?(:clj (extend-protocol bthread/BThread
          clojure.lang.APersistentMap
          (name [this] (get this :name))
          (bid
            ([this] this)
            ([this _event] this))
          (priority [this] (get this :priority 0)))
   :cljs (extend-protocol bthread/BThread

           cljs.core.PersistentArrayMap
           (name [this] (get this :name))
           (bid
             ([this] this)
             ([this _event] this))
           (priority [this] (get this :priority 0))

           cljs.core.PersistentHashMap
           (name [this] (get this name))
           (bid
             ([this] this)
             ([this _event] this))
           (priority [this] (get this :priority 0))))

(extend-protocol bthread/BThread
  nil
  (name [_] nil)
  (bid [this _event] this)
  (priority [_] 0))
