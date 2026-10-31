(ns tech.thomascothran.pavlov.event
  "Functions to work with events in Pavlov.

  Never assume that events are maps. Often they are not.

  Instead, use `tech.thomascothran.pavlov.event/type` and
  `tech.thomascothran.pavlov.event/terminal?`.

  Bprograms match requests by equality (`=`) and waits and blocks by
  `type`. Custom Event implementations must therefore implement `=` and
  `hash` consistently; `:x` and `{:type :x}` share a type but are
  different events."
  (:refer-clojure :exclude [type])
  (:require [tech.thomascothran.pavlov.event.proto :as proto]))

(defn type
  [event]
  (when event (proto/type event)))

(defn terminal?
  [event]
  (when event (proto/terminal? event)))
