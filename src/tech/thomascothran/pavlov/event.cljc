(ns tech.thomascothran.pavlov.event
  (:refer-clojure :exclude [type])
  (:require [tech.thomascothran.pavlov.event.proto :as proto]))

(defn type
  [event]
  (proto/type event))
