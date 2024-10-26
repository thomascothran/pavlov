(ns dev
  (:require [portal.api :as portal]
            [flow-storm.api :as fs-api]
            [cljs.repl.browser :as b]
            [cider.piggieback :as p]
            [nrepl.server :as nrepl]))

(defonce !nrepl-server
  (atom nil))

(defn start-nrepl!
  ([] (start-nrepl! {:port 3276}))
  ([{:keys [port]}]
   (spit ".nrepl-port" port)
   (reset! !nrepl-server
           (nrepl/start-server
            :handler (nrepl/default-handler #'p/wrap-cljs-repl)
            :port port))
   :ok))

(defn go!
  [& _]
  (start-nrepl!)
  @(promise))

(defn run-plain-repl
  []
  (p/cljs-repl (b/repl-env)))

(comment
  ;; In conjure, use ConjurePiggieback and the following
  ;; :ConjurePiggieback (cljs.repl.browser/repl-env)
  (p/cljs-repl (b/repl-env))

  1
  (js/alert "hi")
  :cljs/quit)

(comment
  (fs-api/local-connect))

(comment
  (do
    (def p (portal/open))
    (add-tap #'portal/submit)))
