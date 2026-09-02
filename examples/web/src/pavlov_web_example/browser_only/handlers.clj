(ns pavlov-web-example.browser-only.handlers
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def shell-resource "pavlov_web_example/browser_only/shell.html")

(defn- browser-only-page []
  (some-> shell-resource io/resource slurp))

(defn browser-only-shell
  [request]
  (let [squint? (= :squint (:pavlov/compiler request))
        body (browser-only-page)
        body (if squint?
               (str/replace body
                            "<script src=\"/js/main.js?v=snazzy-demo-1\"></script>"
                            "<script type=\"module\" src=\"/js-squint/main.js\"></script>")
               body)]
    {:status 200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body body}))
