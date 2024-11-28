(ns tech.thomascothran.pavlov.event.defaults
  (:require [tech.thomascothran.pavlov.event.proto :as event]))

(extend-protocol event/Event
  #?(:clj clojure.lang.Keyword
     :cljs Keyword)
  (type [event] event)
  (terminal? [event]
    (= event :pavlov/terminate)))

#?(:clj (extend-protocol event/Event
          clojure.lang.APersistentMap
          (type [event] (:type event))
          (terminal? [event] (:terminal event)))

   :cljs (extend-protocol event/Event

           cljs.core.PersistentArrayMap
           (type [event] (:type event))
           (terminal? [event] (:terminal event))

           cljs.core.PersistentHashMap
           (type [event] (:type event))
           (terminal? [event] (:terminal event)))
   :squint (extend-protocol event/Object
             (type [event] (get event :type))
             (terminal? [event] (get event :terminal))))

(extend-protocol event/Event
  #?(:clj Object :cljs default)
  (type [o] o)
  (terminal? [_] false))

