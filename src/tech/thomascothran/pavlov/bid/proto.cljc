(ns tech.thomascothran.pavlov.bid.proto)

(defprotocol Bid
  (request [this])
  (wait-on [this])
  (block [this]))

