(ns tech.thomascothran.pavlov.bid
  (:require [tech.thomascothran.pavlov.bid.proto :as proto]))

(defn request
  [bid]
  (proto/request bid))

(defn wait-on
  [bid]
  (proto/wait-on bid))

(defn block
  [bid]
  (proto/block bid))

(defn invariant-violated
  [bid]
  (proto/invariant-violated bid))

(defn terminal
  [bid]
  (proto/terminal bid))
