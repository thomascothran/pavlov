(ns tech.thomascothran.pavlov.bid.defaults
  (:require [tech.thomascothran.pavlov.bid.proto :as proto]))

#?(:clj (extend-protocol proto/Bid
          clojure.lang.APersistentMap
          (request [this] (get this :request #{}))
          (wait-on [this] (get this :wait-on #{}))
          (block [this] (get this :block #{})))

   :cljs (extend-protocol proto/Bid

           cljs.core.PersistentArrayMap
           (request [this] (get this :request #{}))
           (wait-on [this] (get this :wait-on #{}))
           (block [this] (get this :block #{}))

           cljs.core.PersistentHashMap
           (request [this] (get this :request #{}))
           (wait-on [this] (get this :wait-on #{}))
           (block [this] (get this :block #{})))

   :squint (extend-protocol proto/Bid
             js/Object
             (request [this] (get this :request #{}))
             (wait-on [this] (get this :wait-on #{}))
             (block [this] (get this :block #{}))))

(extend-protocol proto/Bid
  nil
  (request [_])
  (wait-on [_])
  (block [_]))

