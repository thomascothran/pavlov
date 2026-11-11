(ns tech.thomascothran.pavlov.explorer.demo
  "Example programs for the explorer, also used by its tests.

  Events are deliberately mixed: most are maps, some carry nested maps, and
  some are bare keywords.

    (require '[tech.thomascothran.pavlov.explorer :as explorer]
             '[tech.thomascothran.pavlov.explorer.demo :as demo])
    (explorer/start! {:catalogue #'demo/catalogue})"
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]))
;; ---------------------------------------------------------------------------
;; Account opening: the bank demo from `dev/demo/bank/domain.cljc`, with
;; events that carry data.

(defn make-request-cip-verification-bthread
  []
  (b/scenario [{:wait-on #{:application-submitted}}
               {:request #{:request-cip-verification}}]))

(defn make-request-ofac-screening-bthread
  []
  (b/scenario [{:wait-on #{:application-submitted}}
               {:request #{{:type :ofac-screening-requested
                            :lists [:sdn :consolidated]
                            :subject {:name "Ada Okafor" :country "NG"}}}}]))

(defn make-cip-failure-rule-bthread
  []
  (b/on :cip-failed
        (constantly
         {:request #{{:type :application-declined :reason :cip-failed}}})))

(defn make-ofac-hit-rule-bthread
  []
  (b/on :ofac-hit
        (constantly
         {:request #{{:type :application-declined :reason :ofac-hit}}})))

(defn make-request-initial-deposit-bthread
  []
  (b/scenario [{:request #{:initial-deposit-requested}}]))

(defn make-block-deposit-until-cip-verified
  []
  (b/scenario [{:block #{:initial-deposit-requested}
                :wait-on #{:cip-verified}}]))

(defn make-block-opening-until-ofac-cleared
  []
  (b/scenario [{:block #{:initial-deposit-requested}
                :wait-on #{:ofac-clear}}]))

(defn make-open-on-funding-bthread
  []
  (b/scenario [{:wait-on #{:initial-deposit-paid}}
               {:request #{{:type :account-opened
                            :account {:number "0042-7781"
                                      :kind :checking
                                      :holder {:name "Ada Okafor"}}}}}]))

(defn make-application-submitted-bthread
  []
  (b/scenario
   [{:request #{{:type :application-submitted
                 :product :checking
                 :applicant {:name "Ada Okafor"
                             :date-of-birth "1990-04-12"
                             :address {:street "14 Marina Road"
                                       :city "Lagos"
                                       :country "NG"}}}}}]))

(defn make-pay-deposit-bthread
  []
  (b/scenario [{:request #{{:type :initial-deposit-paid
                            :amount 250
                            :currency "USD"}}}]))

(defn make-account-opening-requires-ofac-screening-bthread
  []
  (b/scenario [{:wait-on #{:account-opened}}
               {:wait-on #{:ofac-clear}
                :request #{{:type :account-opened
                            :invariant-violated true}}}]))

;; ---------------------------------------------------------------------------
;; Order fulfilment: has a loop, a proper ending, a dead end, and two events
;; of the same type that differ only in their data.

(defn make-customer-bthread
  []
  (b/scenario
   [{:request #{{:type :order/placed
                 :order {:id "ord-1042"
                         :customer {:name "Sam Reyes" :tier :gold}
                         :items [{:sku "kb-75" :qty 1 :price 80}
                                 {:sku "cable-usb-c" :qty 2 :price 10}]
                         :total 100}}}}]))

(defn make-payment-bthread
  []
  (b/step
   (fn [state event]
     (let [ask [:awaiting-payment
                {:request #{{:type :payment/received
                             :amount 100
                             :method {:kind :card :last4 "4242"}}
                            {:type :payment/received
                             :amount 50
                             :method {:kind :gift-card :code "GC-7F2"}}
                            {:type :payment/declined
                             :reason :insufficient-funds
                             :attempt {:method {:kind :card :last4 "4242"}
                                       :processor {:code "51"
                                                   :message "Insufficient funds"}}}}}]]
       (case [state (e/type event)]
         [nil nil] [:no-order {:wait-on #{:order/placed}}]
         [:no-order :order/placed] ask
         [:awaiting-payment :payment/declined] [:declined {:wait-on #{:payment/retry-requested}}]
         [:declined :payment/retry-requested] ask
         [:paid nil])))))

(defn make-retry-declined-payment-bthread
  []
  (b/step
   (fn [state event]
     (if (and (= :waiting state) (= :payment/declined (e/type event)))
       [:retrying {:request #{:payment/retry-requested}}]
       [:waiting {:wait-on #{:payment/declined}}]))))

(defn make-ship-when-paid-bthread
  []
  (b/scenario [{:wait-on #{:payment/received}}
               {:request #{{:type :order/shipped
                            :shipment {:carrier "DHL"
                                       :tracking "JD014600003"
                                       :to {:city "Porto" :country "PT"}}}}}
               {:request #{{:type :order/completed :terminal true}}}]))

(defn make-hold-part-paid-orders-bthread
  []
  (b/step
   (fn [state event]
     (cond
       (nil? state) [:watching {:wait-on #{:payment/received}}]
       (and (= :watching state) (< (:amount event 0) 100))
       [:holding {:block #{:order/shipped}}]
       (= :holding state) [:holding {:block #{:order/shipped}}]
       :else [:satisfied nil]))))

;; ---------------------------------------------------------------------------
;; Ticket queue: never ends, so exploration stops at the configured limit.

(defn make-open-tickets-bthread
  []
  (b/step
   (fn [state _event]
     (let [n (inc (or state -1))]
       [n {:request #{{:type :ticket/opened
                       :number (inc n)
                       :from {:channel :email}}}}]))))

(defn make-escalate-every-third-ticket-bthread
  []
  (b/step
   (fn [state event]
     (let [n (if (= :ticket/opened (e/type event)) (inc (or state 0)) (or state 0))]
       (if (and (pos? n) (zero? (mod n 3)) (= :ticket/opened (e/type event)))
         [n {:request #{:ticket/escalated}}]
         [n {:wait-on #{:ticket/opened}}])))))

;; ---------------------------------------------------------------------------
;; Catalogue

(defn make-account-opening-config
  []
  {:bthreads {:bank/request-cip-verification (make-request-cip-verification-bthread)
              :bank/request-ofac-screening (make-request-ofac-screening-bthread)
              :bank/cip-failure-rule (make-cip-failure-rule-bthread)
              :bank/ofac-hit-rule (make-ofac-hit-rule-bthread)
              :bank/request-initial-deposit (make-request-initial-deposit-bthread)
              :bank/block-deposit-until-cip-verified (make-block-deposit-until-cip-verified)
              :bank/block-opening-until-ofac-cleared (make-block-opening-until-ofac-cleared)
              :bank/open-on-funding (make-open-on-funding-bthread)}
   :environment-bthreads {:bank/application-submitted (make-application-submitted-bthread)
                          :bank/pay-deposit (make-pay-deposit-bthread)}
   :safety-bthreads {:bank/account-opening-requires-ofac-screening
                     (make-account-opening-requires-ofac-screening-bthread)}
   :check-deadlock? false})

(defn make-order-fulfilment-config
  []
  {:bthreads {:ship-when-paid (make-ship-when-paid-bthread)
              :retry-declined-payment (make-retry-declined-payment-bthread)
              :hold-part-paid-orders (make-hold-part-paid-orders-bthread)}
   :environment-bthreads {:customer (make-customer-bthread)
                          :payment (make-payment-bthread)}})

(defn make-ticket-queue-config
  []
  {:bthreads {:escalate-every-third-ticket (make-escalate-every-third-ticket-bthread)}
   :environment-bthreads {:open-tickets (make-open-tickets-bthread)}
   :max-nodes 8})

(def catalogue
  [{:id :account-opening
    :name "Account opening"
    :description "Opening a bank account: identity (CIP) and sanctions (OFAC) checks, the initial deposit, and the rule that an account must not open before sanctions are cleared."
    :make-config #'make-account-opening-config
    :sources {:bank/request-cip-verification #'make-request-cip-verification-bthread
              :bank/request-ofac-screening #'make-request-ofac-screening-bthread
              :bank/cip-failure-rule #'make-cip-failure-rule-bthread
              :bank/ofac-hit-rule #'make-ofac-hit-rule-bthread
              :bank/request-initial-deposit #'make-request-initial-deposit-bthread
              :bank/block-deposit-until-cip-verified #'make-block-deposit-until-cip-verified
              :bank/block-opening-until-ofac-cleared #'make-block-opening-until-ofac-cleared
              :bank/open-on-funding #'make-open-on-funding-bthread
              :bank/application-submitted #'make-application-submitted-bthread
              :bank/pay-deposit #'make-pay-deposit-bthread
              :bank/account-opening-requires-ofac-screening
              #'make-account-opening-requires-ofac-screening-bthread}}

   {:id :order-fulfilment
    :name "Order fulfilment"
    :description "A customer places an order and pays. Declined payments can be retried, full payment ships the order, and part payment puts it on hold."
    :make-config #'make-order-fulfilment-config
    :sources {:ship-when-paid #'make-ship-when-paid-bthread
              :retry-declined-payment #'make-retry-declined-payment-bthread
              :hold-part-paid-orders #'make-hold-part-paid-orders-bthread
              :customer #'make-customer-bthread
              :payment #'make-payment-bthread}}

   {:id :ticket-queue
    :name "Ticket queue"
    :description "Support tickets keep arriving and every third one is escalated. This program never ends, so it is only explored up to a limit."
    :make-config #'make-ticket-queue-config
    :sources {:escalate-every-third-ticket #'make-escalate-every-third-ticket-bthread
              :open-tickets #'make-open-tickets-bthread}}])
