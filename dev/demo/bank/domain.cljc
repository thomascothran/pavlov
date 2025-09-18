(ns demo.bank.domain
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.viz.portal :as pvp]
            [tech.thomascothran.pavlov.model.check :as check]))

(defn make-request-cip-verification-bthread
  []
  (b/bids [{:wait-on #{:application-submitted}}
           {:request #{{:type :request-cip-verification}}}]))

(defn make-request-ofac-screening-bthread
  []
  (b/bids [{:wait-on #{:application-submitted}}
           {:request #{{:type :request-ofac-screening}}}]))

(defn make-cip-failure-rule-bthread
  []
  (b/on :cip-failed
        (constantly
         {:request #{{:type :application-declined}}})))

(defn make-ofac-hit-rule-bthread
  []
  (b/on :ofac-hit
        (constantly
         {:request #{{:type :application-declined}}})))

(defn make-request-initial-deposit-bthread
  []
  (b/bids [{:request #{{:type :request-initial-deposit}}}]))

(defn make-block-deposit-until-cip-verified
  []
  (b/bids [{:block #{:request-initial-deposit}
            :wait-on #{:cip-verified}}]))

(defn make-block-deposit-until-ofac-cleared
  []
  (b/bids [{:block #{:request-initial-deposit}
            :wait-on #{:ofac-clear}}]))

;; When initial funds arrive, open the account (happy-path terminator).
(defn make-open-on-funding-bthread
  []
  (b/bids [{:wait-on #{:initial-deposit-paid}}
           {:request #{{:type :account-opened}}}]))

(defn make-bthreads-v1
  []
  {::request-cip-verification-bthread
   (make-request-cip-verification-bthread)

   ::request-ofac-screening-bthread
   (make-request-ofac-screening-bthread)

   ::cip-failure-rule-bthread
   (make-cip-failure-rule-bthread)

   ::ofac-hit-rule-bthread
   (make-ofac-hit-rule-bthread)

   ::request-initial-deposit-bthread
   (make-request-initial-deposit-bthread)

   ::block-deposit-until-cip-verified
   (make-block-deposit-until-cip-verified)

   ::block-deposit-until-ofac-cleared
   (make-block-deposit-until-ofac-cleared)

   ::open-on-funding
   (make-open-on-funding-bthread)})

(defn make-environment-bthreads-v1
  []
  {::application-submitted
   (b/bids
    [{:request #{{:type :application-submitted}}}])
   ::pay-deposit
   (b/bids [{:request #{{:type :initial-deposit-paid}}}])})

(defn safety-bthreads-v1
  []
  {::account-opening-requires-ofac-screening
   (let [waits #{:account-opened
                 :ofac-clear}]
     (b/thread [state _event]
       :pavlov/init
       [{:initialized true}
        {:wait-on waits}]

       :ofac-clear
       [(assoc state :ofac-clear true)
        {:wait-on waits}]

       :account-opened
       [state (if (get state :ofac-clear)
                {:wait-on #{waits}}
                {:request #{{:type ::account-opened-without-ofac
                             :invariant-violated true}}})]

       [state {:waits waits}]))})

(comment
  ;; Uh oh - we can open an account before the ofac is clear!
  (check/check {:bthreads (make-bthreads-v1)
                :environment-bthreads (make-environment-bthreads-v1)
                :safety-bthreads (safety-bthreads-v1)
                :check-deadlock? false #_true}))

(comment
  (-> (reduce into (safety-bthreads-v1)
              [(make-bthreads-v1)
               (make-environment-bthreads-v1)])

      (pvp/bthreads->navigable)
      (tap>)))

;; More rules to consider:
;;
;; - PEPs
;; - Identity Theft Alerts
;; - Liquidity stress
;; - Large initial deposits
