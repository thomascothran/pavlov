(ns pavlov-web-example.routes-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pavlov-web-example.server :as server]))

(defn- response-for [uri]
  ((server/app-handler) {:request-method :get
                         :uri uri}))

(deftest browser-only-route-serves-shell-with-stable-initialize-button-id
  (let [{:keys [status headers body]} (response-for "/browser-only")
        content-type (or (get headers "content-type") "")
        body (or body "")]
    (is (= 200 status))
    (is (str/starts-with? content-type "text/html"))
    (is (str/includes? body "id=\"browser-only-initialize-button\""))))

(deftest squint-routes-load-the-module-bundle-without-the-shadow-bundle
  (doseq [uri ["/browser-only-squint" "/game-of-life-squint"]]
    (let [{:keys [status body]} (response-for uri)]
      (is (= 200 status))
      (is (str/includes? body "type=\"module\""))
      (is (str/includes? body "/js-squint/main.js"))
      (is (not (str/includes? body "/js/main.js"))))))

(deftest browser-only-websocket-route-exposes-ring-websocket-listener
  (let [response (response-for "/browser-only/ws/")
        listener (:ring.websocket/listener response)]
    (testing "the example app exposes a websocket handler at the mounted browser-only path"
      (is (map? response))
      (is (map? listener))
      (is (fn? (:on-open listener)))
      (is (fn? (:on-message listener))))))
