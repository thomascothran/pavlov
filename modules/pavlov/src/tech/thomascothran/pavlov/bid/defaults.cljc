(ns tech.thomascothran.pavlov.bid.defaults
  (:require [tech.thomascothran.pavlov.bid.proto :as proto]))

(defn- waited-events
  [bid]
  (reduce disj
          (get bid :wait-on #{})
          (get bid :request #{})))

#?(:clj (extend-protocol proto/Bid
          clojure.lang.APersistentMap
          (request [this] (get this :request #{}))
          (wait-on [this] (waited-events this))
          (block [this] (get this :block #{}))
          (bthreads [this] (get this :bthreads))
          (hot [this] (get this :hot)))

   :squint (extend-protocol proto/Bid
             object
             (request [this] (get this :request #{}))
             (wait-on [this] (waited-events this))
             (block [this] (get this :block #{}))
             (bthreads [this] (get this :bthreads))
             (hot [this] (get this :hot)))

   :cljs (extend-protocol proto/Bid

           cljs.core.PersistentArrayMap
           (request [this] (get this :request #{}))
           (wait-on [this] (waited-events this))
           (block [this] (get this :block #{}))
           (bthreads [this] (get this :bthreads))
           (hot [this] (get this :hot))

           cljs.core.PersistentHashMap
           (request [this] (get this :request #{}))
           (wait-on [this] (waited-events this))
           (block [this] (get this :block #{}))
           (bthreads [this] (get this :bthreads))
           (hot [this] (get this :hot))))

(extend-protocol proto/Bid
  nil
  (request [_])
  (wait-on [_])
  (block [_])
  (bthreads [_])
  (hot [_]))
