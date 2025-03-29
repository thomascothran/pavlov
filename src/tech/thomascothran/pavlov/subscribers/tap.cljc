(ns tech.thomascothran.pavlov.subscribers.tap
  "Experimental, will likely change."
  (:require [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.bid.proto :as bid]))

(defn- event->bthreads
  [bthread->bid]
  (reduce (fn [acc [bthread-name bid]]
            (let [wait-on (bid/wait-on bid)
                  block   (bid/block bid)
                  request (bid/request bid)

                  reducer-fn
                  (fn [bid-type acc' event]
                    (update-in acc' [bid-type (event/type event)]
                               #(into #{bthread-name} %)))

                  update-bid-bthreads
                  (fn [acc' bid-type]
                    (reduce (partial reducer-fn bid-type) acc'
                            (case bid-type
                              :wait-on wait-on
                              :block   block
                              :request request)))]
              (-> acc
                  (update-bid-bthreads :wait-on)
                  (update-bid-bthreads :block)
                  (update-bid-bthreads :request))))

          {}
          bthread->bid))

(defn subscriber
  ([event bthread->bid]
   (subscriber :tap event bthread->bid))
  ([prefix event bthread->bid]
   (try
     (let [event->bthreads' (event->bthreads bthread->bid)

           waiting-on (into #{} (keys (get event->bthreads' :wait-on)))
           requested  (into #{} (keys (get event->bthreads' :request)))
           blocked    (into #{} (keys (get event->bthreads' :block)))
           unblocked  (into #{} (remove (into #{} blocked)) requested)]

       (tap> {:subscriber-name prefix
              :event event
              :blocked blocked
              :requested requested
              :unblocked unblocked
              :waiting-on waiting-on
              :event->bthreads event->bthreads'
              :bthread->bid bthread->bid}))
     (catch #?(:clj Throwable :cljs :default) e
       (tap> {:subscriber-name prefix
              :event event
              :error-msg (str "Error in tap subscriber: " (.getMessage e))
              :error e})))))

(comment
  (do
    (require '[portal.api :as portal])
    (def p (portal/open))
    (add-tap #'portal/submit)))
