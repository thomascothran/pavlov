(ns tech.thomascothran.pavlov.subscribers.portal
  "Experimental, will likely change."
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn- ->portal-table
  [x]
  (if (map? x)
    (vary-meta x assoc :portal.viewer/default :portal.viewer/table)
    x))

(defn subscriber
  ([event bthread->bid]
   (subscriber :portal event bthread->bid))
  ([prefix event bthread->bid]
   (let [bthread->bid'
         (into {}
               (map (fn [[bthread bids]]
                      [(or (b/name bthread) bthread)
                       (->portal-table bids)]))
               (->portal-table bthread->bid))]
     (tap> [prefix event bthread->bid']))))
