(ns tech.thomascothran.pavlov.bthread.defaults
  (:require [tech.thomascothran.pavlov.bthread.proto :as bthread]))

#?(:clj (extend-protocol bthread/BThread
          clojure.lang.APersistentMap
          (name [this] this)
          (bid [this _event] this)
          (serialize [this] this)
          (deserialize [_this serialized] serialized))

   :cljs (extend-protocol bthread/BThread

           cljs.core.PersistentArrayMap
           (name [this] this)
           (bid [this _event] this)
           (serialize [this] this)
           (deserialize [_this serialized] serialized)

           cljs.core.PersistentHashMap
           (name [this] this)
           (bid [this _event] this)
           (serialize [this] this)
           (deserialize [_this serialized] serialized)))

(extend-protocol bthread/BThread
  nil
  (name [_] nil)
  (bid [this _event] this)
  (serialize [_] nil)
  (deserialize [_ _] nil))
