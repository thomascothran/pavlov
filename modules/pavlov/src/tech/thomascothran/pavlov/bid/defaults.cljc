(ns tech.thomascothran.pavlov.bid.defaults
  (:require [tech.thomascothran.pavlov.bid.proto :as proto]
            [clojure.set :as set]))

#?(:clj (extend-protocol proto/Bid
          clojure.lang.APersistentMap
          (request [this] (get this :request #{}))
          (wait-on [this] (-> (get this :wait-on #{})
                              (set/difference (get this :request #{}))))
          (block [this] (get this :block #{}))
          (invariant-violated [this] (get this :invariant-violated))
          (terminal [this] (get this :terminal)))

   :cljs (extend-protocol proto/Bid

           cljs.core.PersistentArrayMap
           (request [this] (get this :request #{}))
           (wait-on [this] (-> (get this :wait-on #{})
                               (set/difference (get this :request #{}))))
           (block [this] (get this :block #{}))
           (invariant-violated [this] (get this :invariant-violated))
           (terminal [this] (get this :terminal))

           cljs.core.PersistentHashMap
           (request [this] (get this :request #{}))
           (wait-on [this] (-> (get this :wait-on #{})
                               (set/difference (get this :request #{}))))
           (block [this] (get this :block #{}))
           (invariant-violated [this] (get this :invariant-violated))
           (terminal [this] (get this :terminal)))

   :squint (extend-protocol proto/Bid
             js/Object
             (request [this] (get this :request #{}))
             (wait-on [this] (-> (get this :wait-on #{})
                                 (set/difference (get this :request #{}))))
             (block [this] (get this :block #{}))
             (invariant-violated [this] (get this :invariant-violated))
             (terminal [this] (get this :terminal))))

(extend-protocol proto/Bid
  nil
  (request [_])
  (wait-on [_])
  (block [_])
  (invariant-violated [_])
  (terminal [_]))
