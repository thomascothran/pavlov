(ns pavlov-web-example.routes-test
  (:require [clojure.string :as str]
             [clojure.test :refer [deftest is]]
             [pavlov-web-example.server :as server]))

(defn- response-for [uri]
  ((server/app-handler) {:request-method :get
                         :uri uri}))

(deftest browser-only-route-serves-static-shell
  (let [{:keys [status headers body]} (response-for "/browser-only")
        content-type (or (get headers "content-type") "")
        body (or body "")]
    (is (= 200 status))
    (is (string? body))
    (is (str/starts-with? content-type "text/html"))
    (is (str/includes? body "id=\"browser-only-root\""))
    (is (str/includes? body "id=\"simple-web-page-form\""))
    (is (str/includes? body "id=\"simple-web-page-input\""))
    (is (str/includes? body "pavlov-on-input=\":browser-only/input\""))
    (is (str/includes? body "pavlov-on-reset=\":browser-only/reset\""))
    (is (str/includes? body "id=\"telemetry-grid-search\""))
    (is (str/includes? body "pavlov-on-input=\":grid/search-input\""))
    (is (str/includes? body "data-browser-only-status"))
    (is (str/includes? body "NEURAL_TELEMETRY_LOG"))
    (is (str/includes? body "SYNTH-NODE-042"))
    (is (str/includes? body "pavlov-on-click=\":grid/sort-clicked\""))
    (is (str/includes? body "pavlov-capture-selector=\"#telemetry-grid-body > [data-grid-row], #telemetry-grid-body > [data-grid-row] > [pavlov-node-id-sort-value]\""))
    (is (str/includes? body "id=\"telemetry-row-042\""))
    (is (str/includes? body "pavlov-latency-sort-value=\"12.5\""))
    (is (str/includes? body "pavlov-search-value=\"SYNTH-NODE-042 syncing degraded pavlov/tcp l4_partial\""))
    (is (str/includes? body "data-grid-row"))
    (is (str/includes? body "/js/main.js"))))

(deftest missing-route-falls-through-to-default-404
  (let [{:keys [status]} (response-for "/missing")]
    (is (= 404 status))))

(defn -main []
  (let [{:keys [fail error]} (clojure.test/run-tests 'pavlov-web-example.routes-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
