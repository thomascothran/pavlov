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
           {:request #{{:type :ofac-screening-requested}}}]))

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
  (b/bids [{:request #{{:type :initial-deposit-requested}}}]))

(defn make-block-deposit-until-cip-verified
  []
  (b/bids [{:block #{:initial-deposit-requested}
            :wait-on #{:cip-verified}}]))

(defn make-block-opening-until-ofac-cleared
  []
  (b/bids [{:block #{:initial-deposit-requested}
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

   ::block-opening-until-ofac-cleared
   (make-block-opening-until-ofac-cleared)

   ::open-on-funding
   (make-open-on-funding-bthread)})

(defn make-environment-bthreads-v1
  []
  {::application-submitted
   (b/bids
    [{:request #{{:type :application-submitted}}}])
   ::pay-deposit
   (b/bids [{:request #{{:type :initial-deposit-paid}}}])})

(defn make-account-opening-requires-ofac-screening-bthread
  []
  (b/bids [{:wait-on #{:account-opened}}
           {:wait-on #{:ofac-clear}
            :request #{{:type :account-opened
                        :invariant-violated true}}}]))

(defn safety-bthreads-v1
  []
  {::account-opening-requires-ofac-screening
   (make-account-opening-requires-ofac-screening-bthread)})

(comment
  ;; Uh oh - we can open an account before the ofac is clear!
  (-> {:bthreads (make-bthreads-v1)
       :environment-bthreads (make-environment-bthreads-v1)
       :safety-bthreads (safety-bthreads-v1)
       :check-deadlock? false #_true}
      (check/check)
      tap>))

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
;; - Litigation hold
;; - Bankruptcy
