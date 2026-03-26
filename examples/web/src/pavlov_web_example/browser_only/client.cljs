(ns pavlov-web-example.browser-only.client
  (:require [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
            [tech.thomascothran.pavlov.bthread :as b]
            [pavlov-web-example.browser-only.client.datagrid.in-memory :as datagrid.in-memory]
            [tech.thomascothran.pavlov.web.dom :as dom]))

(def root-selector "#browser-only-root")
(def status-selector "[data-browser-only-status]")

(defn- status-text
  [event]
  (case (:type event)
    :browser-only/reset "idle"
    (get-in event [:dom/input :value] "")))

(defn- make-program [query-selector]
  (bpe/make-program!
   (into [[:dom-op (dom/make-dom-op-bthread query-selector)]
          [:dom-event-redirect
           (dom/make-dom-event-redirect-bthread)]
          [:mirror-input
           (b/on-any #{:browser-only/input
                       :browser-only/reset}
                     (fn [event]
                       {:request #{{:type :pavlov.web.dom/op
                                    :selector status-selector
                                    :kind :set
                                    :member "textContent"
                                    :value (status-text event)}}}))]]
         (datagrid.in-memory/make-bthreads))))

(defn init! []
  (let [query-selector #(.querySelectorAll js/document %)
        program (make-program query-selector)
        root (.querySelector js/document root-selector)]
    (dom/attach-dom-events! {:root root
                             :submit! #(bp/submit-event! program %)})))
