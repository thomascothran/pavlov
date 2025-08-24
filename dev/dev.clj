(ns dev
  (:require [portal.api :as portal]
            [flow-storm.api :as fs-api]
            [cljs.repl.browser :as b]
            [cider.piggieback :as p]
            [nrepl.server :as nrepl]
            [shadow.cljs.devtools.server.nrepl :as shadow-nrepl]
            [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.api :as shadow]))

(comment
  (require '[clojure.repl.deps :as d])
  (d/add-libs
   {'lambdaisland/kaocha {:mvn/version "1.91.1392"}}))

(defonce !nrepl-server
  (atom nil))

(defn start-nrepl!
  ([] (start-nrepl! {:port 3276}))
  ([{:keys [port]}]
   (spit ".nrepl-port" port)
   (reset! !nrepl-server
           (nrepl/start-server
            :handler (nrepl/default-handler #'shadow-nrepl/middleware #'p/wrap-cljs-repl)
            :port port))
   (println "nrepl started on port " port)
   :ok))

(defn shadow-go!
  []
  (do
    (server/start!)
    (shadow/watch :test)))

(comment
  (shadow/repl :test))

(defn go!
  [& _]
  (start-nrepl!)
  (shadow-go!)
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
    (require '[portal.api :as portal])
    (def p (portal/open))
    (add-tap #'portal/submit)))
