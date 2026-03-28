(ns pavlov-web-example.browser-only.client
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [pavlov-web-example.browser-only.client.datagrid.in-memory :as datagrid.in-memory]
            [pavlov-web-example.client.runtime :as runtime]
            [tech.thomascothran.pavlov.web.dom :as dom]))

(def status-selector "[data-browser-only-status]")
(def initialize-selector "nav button")
(def page-id :browser-only)

(defn- status-text
  [event]
  (case (:type event)
    :browser-only/reset "idle"
    (get-in event [:dom/input :value] "")))

(defn- make-page-bthreads []
  (into [[:mirror-input
          (b/on-any #{:browser-only/input
                      :browser-only/reset}
                    (fn [event]
                      {:request #{{:type :pavlov.web.dom/op
                                   :selector status-selector
                                   :kind :set
                                   :member "textContent"
                                   :value (status-text event)}}}))]]
        (datagrid.in-memory/make-bthreads)))

(defn init! []
  (runtime/init! {:ws-path "/browser-only/ws/"
                  :make-program (fn [{:keys [query-selector submit! transport]}]
                                  (runtime/make-bridged-program!
                                   {:query-selector query-selector
                                    :submit! submit!
                                    :transport transport
                                    :forwarded-events #{:browser-only/initialize-clicked}
                                    :page-bthreads (make-page-bthreads)}))}))

(defn mounted?
  []
  (= "browser-only"
     (.getAttribute (.-body js/document) "data-pavlov-page")))

(def page
  {:page-id page-id
   :mounted? mounted?
   :init! init!})
