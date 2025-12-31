(ns tech.thomascothran.pavlov.event
  "Functions to work with events in Pavlov.

  Never assume that events are maps. Often they are not.

  Instead, use `tech.thomascothran.pavlov.event/type` and
  `tech.thomascothran.pavlov.event/terminal?`."
  (:refer-clojure :exclude [type])
  (:require [tech.thomascothran.pavlov.event.proto :as proto]))

(defn type
  [event]
  (when event (proto/type event)))

(defn terminal?
  [event]
  (when event (proto/terminal? event)))
