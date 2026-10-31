(ns tech.thomascothran.pavlov.bprogram.request-matching-test
  "Requests are satisfied by an equal event, for any Event implementation.
  Waits and blocks match by event type."
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [tech.thomascothran.pavlov.event.defaults]
            [tech.thomascothran.pavlov.bid.defaults]
            [tech.thomascothran.pavlov.bthread.defaults]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event.proto :as event-proto]
            [tech.thomascothran.pavlov.bprogram.state :as s]))

(deftype Order [entree]
  event-proto/Event
  (type [_] :order-food)
  (terminal? [_] false)

  #?@(:clj
      [Object
       (equals [_ other]
               (and (instance? Order other)
                    (= entree (.-entree ^Order other))))
       (hashCode [_] (hash [::order entree]))]
      :cljs
      [IEquiv
       (-equiv [_ other]
               (and (instance? Order other)
                    (= entree (.-entree ^Order other))))
       IHash
       (-hash [_] (hash [::order entree]))]))

(defrecord Served [entree])

(extend-type Served
  event-proto/Event
  (type [_] :served)
  (terminal? [_] false))

(defn- identity-order
  "An event equal only to itself."
  []
  (reify event-proto/Event
    (type [_] :order-food)
    (terminal? [_] false)))

(defn- customer [order served]
  (b/bids [{:request #{order}}
           {:request #{served}}]))

(defn- run-events
  [bthreads]
  (loop [state (s/init bthreads)
         events []]
    (if-some [event (:next-event state)]
      (recur (s/step state event) (conj events event))
      events)))

(deftest deftype-and-record-requests-match-by-equality
  (is (= [(->Order :steak) (->Served :steak)
          (->Order :vegan-salad) (->Served :vegan-salad)]
         (run-events [[:alice (customer (->Order :steak) (->Served :steak))]
                      [:bob (customer (->Order :vegan-salad)
                                      (->Served :vegan-salad))]]))))

(deftest equal-deftype-instances-satisfy-each-other
  (let [state (s/init [[:alice (customer (->Order :steak) (->Served :alice))]])
        next-state (s/step state (->Order :steak))]
    (is (= (->Served :alice) (:next-event next-state))
        "A distinct but equal instance satisfies the request")))

(deftest identity-events-match-only-themselves
  (let [alice-order (identity-order)
        carol-order (identity-order)
        events (run-events [[:alice (customer alice-order (->Served :alice))]
                            [:carol (customer carol-order (->Served :carol))]])]
    (is (= 4 (count events)))
    (is (identical? alice-order (nth events 0)))
    (is (= (->Served :alice) (nth events 1)))
    (is (identical? carol-order (nth events 2)))
    (is (= (->Served :carol) (nth events 3)))))

(deftest custom-events-in-waits-and-blocks-mean-their-type
  (let [!seen (atom [])
        auditor (b/step (fn [_ event]
                          (when event (swap! !seen conj event))
                          [nil {:wait-on #{(->Order :steak)}}]))]
    (run-events [[:auditor auditor]
                 [:bob (customer (->Order :vegan-salad) (->Served :vegan-salad))]])
    (is (= [(->Order :vegan-salad)] @!seen)))
  (is (= [:done]
         (run-events [[:no-steak {:block #{(->Order :steak)}}]
                      [:bob (customer (->Order :vegan-salad) (->Served :vegan-salad))]
                      [:fallback (b/bids [{:request #{:done}}])]]))))
