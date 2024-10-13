(ns tech.thomascothran.pavlov.event.defaults
  (:require [tech.thomascothran.pavlov.event.proto :as event]))

(extend-protocol event/Event
  #?(:clj clojure.lang.Keyword
     :cljs Keyword)
  (type [event] event)
  (terminal? [event]
    (= event :pavlov/terminate)))

(extend-protocol event/Event
  #?(:clj clojure.lang.APersistentMap
     :cljs PersistentHashMap)
  (type [event] (:type event))
  (terminal? [event]
    (:terminal event)))
