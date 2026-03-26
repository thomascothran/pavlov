(ns pavlov-web-example.browser-only.handlers
  (:require [clojure.java.io :as io]))

(def shell-resource "pavlov_web_example/browser_only/shell.html")

(defn- browser-only-page []
  (some-> shell-resource io/resource slurp))

(defn browser-only-shell
  [_]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body (browser-only-page)})
