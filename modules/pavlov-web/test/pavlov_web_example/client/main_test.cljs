(ns pavlov-web-example.client.main-test
  (:require [cljs.test :refer-macros [deftest is]]
            [pavlov-web-example.browser-only.client :as browser-only]
            [pavlov-web-example.client.main :as main]
            ["jsdom" :refer [JSDOM]]))

(defn- with-dom
  [body-attrs f]
  (let [dom (JSDOM. (str "<body" body-attrs "></body>")
                    #js {:url "http://localhost/browser-only"})
        window (.-window dom)
        document (.-document window)]
    (set! js/global.window window)
    (set! js/global.document document)
    (f document window)))

(deftest init-dispatches-to-browser-only-page-when-page-marker-is-present
  (let [!called? (atom false)]
    (with-redefs [main/pages [{:page-id :browser-only
                               :mounted? browser-only/mounted?
                               :init! #(reset! !called? true)}]]
      (with-dom " data-pavlov-page=\"browser-only\""
        (fn [_document _window]
          (main/init!)
          (is (true? @!called?)))))))

(deftest init-does-nothing-when-no-known-page-marker-exists
  (let [!called? (atom false)]
    (with-redefs [main/pages [{:page-id :browser-only
                               :mounted? browser-only/mounted?
                               :init! #(reset! !called? true)}]]
      (with-dom ""
        (fn [_document _window]
          (is (nil? (main/init!)))
          (is (false? @!called?)))))))
