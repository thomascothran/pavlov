(ns tech.thomascothran.pavlov.event.publisher
  (:require [tech.thomascothran.pavlov.event.publisher.proto :as proto]))

(defn notify!
  [publisher event]
  (proto/notify! publisher event))

(defn subscribe!
  [publisher key f]
  (proto/subscribe! publisher key f))

(defn listeners
  [publisher]
  (proto/listeners publisher))

