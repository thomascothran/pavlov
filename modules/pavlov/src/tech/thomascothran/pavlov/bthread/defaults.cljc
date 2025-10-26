(ns tech.thomascothran.pavlov.bthread.defaults
  (:require [tech.thomascothran.pavlov.bthread.proto :as bthread]))

#?(:clj (extend-protocol bthread/BThread
          clojure.lang.APersistentMap
          (notify! [this _event] this)
          (state [this] this)
          (label [this] this)
          (set-state [_this serialized] serialized))

   :cljs (extend-protocol bthread/BThread

           cljs.core.PersistentArrayMap
           (notify! [this _event] this)
           (state [this] this)
           (label [this] this)
           (set-state [_this serialized] serialized)

           cljs.core.PersistentHashMap
           (notify! [this _event] this)
           (state [this] this)
           (label [this] this)
           (set-state [_this serialized] serialized)))

(extend-protocol bthread/BThread
  nil
  (notify! [this _event] this)
  (label [_] nil)
  (state [_] nil)
  (set-state [_ _] nil))
