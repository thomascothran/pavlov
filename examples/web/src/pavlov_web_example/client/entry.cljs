(ns pavlov-web-example.client.entry
  (:require [pavlov-web-example.client.main :as main]))

(defonce runtime
  (main/init!))

;; The example exposes its lifecycle handle for browser smoke tests and manual
;; cleanup from devtools. Library consumers do not depend on this global.
(set! (.-pavlovWebExample js/globalThis) runtime)
