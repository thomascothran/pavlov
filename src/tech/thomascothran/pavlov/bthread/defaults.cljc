(ns tech.thomascothran.pavlov.bthread.defaults
  (:require [tech.thomascothran.pavlov.bthread.proto :as bthread]))

#?(:clj (extend-protocol bthread/BThread
          clojure.lang.APersistentMap
          (bid [this _event] this)
          (state [this] this)
          (set-state [_this serialized] serialized))

   :cljs (extend-protocol bthread/BThread

           cljs.core.PersistentArrayMap
           (bid [this _event] this)
           (state [this] this)
           (set-state [_this serialized] serialized)

           cljs.core.PersistentHashMap
           (bid [this _event] this)
           (state [this] this)
           (set-state [_this serialized] serialized)))

(extend-protocol bthread/BThread
  nil
  (bid [this _event] this)
  (state [_] nil)
  (set-state [_ _] nil))
