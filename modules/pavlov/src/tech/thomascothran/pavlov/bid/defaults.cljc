(ns tech.thomascothran.pavlov.bid.defaults
  (:require [tech.thomascothran.pavlov.bid.proto :as proto]
            [clojure.set :as set]))

#?(:clj (extend-protocol proto/Bid
          clojure.lang.APersistentMap
          (request [this] (get this :request #{}))
          (wait-on [this] (-> (get this :wait-on #{})
                              (set/difference (get this :request #{}))))
          (block [this] (get this :block #{}))
          (bthreads [this] (get this :bthreads)))

   :cljs (extend-protocol proto/Bid

           cljs.core.PersistentArrayMap
           (request [this] (get this :request #{}))
           (wait-on [this] (-> (get this :wait-on #{})
                               (set/difference (get this :request #{}))))
           (block [this] (get this :block #{}))
           (bthreads [this] (get this :bthreads))

           cljs.core.PersistentHashMap
           (request [this] (get this :request #{}))
           (wait-on [this] (-> (get this :wait-on #{})
                               (set/difference (get this :request #{}))))
           (block [this] (get this :block #{}))
           (bthreads [this] (get this :bthreads)))

   :squint (extend-protocol proto/Bid
             js/Object
             (request [this] (get this :request #{}))
             (wait-on [this] (-> (get this :wait-on #{})
                                 (set/difference (get this :request #{}))))
             (block [this] (get this :block #{}))
             (bthreads [this] (get this :bthreads))))

(extend-protocol proto/Bid
  nil
  (request [_])
  (wait-on [_])
  (block [_])
  (bthreads [_]))
