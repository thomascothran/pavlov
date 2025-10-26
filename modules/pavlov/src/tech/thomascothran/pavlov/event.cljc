(ns tech.thomascothran.pavlov.event
  (:refer-clojure :exclude [type])
  (:require [tech.thomascothran.pavlov.event.proto :as proto]))

(defn type
  [event]
  (when event (proto/type event)))

(defn terminal?
  [event]
  (when event (proto/terminal? event)))
