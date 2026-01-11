(ns tech.thomascothran.pavlov.bid.proto
  "Protocol for Bid representations used in Pavlov.

  Maps are implemented by default in `tech.thomascothran.pavlov.bid.default`

  Do not assume that the bid is a map.")

(defprotocol Bid
  (request [this])
  (wait-on [this])
  (block [this])
  (bthreads [this]))
