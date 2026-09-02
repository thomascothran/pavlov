(ns pavlov-web-runner
  (:require [cljs.test :as test]
            [pavlov-web-example.browser-only.client-test]
            [pavlov-web-example.client.main-test]
            [pavlov-web-example.client.runtime-test]
            [pavlov-web-example.game-of-life.client-test]
            [tech.thomascothran.pavlov.web.client.websocket-connection-test]
            [tech.thomascothran.pavlov.web.dom.scheduler-test]
            [tech.thomascothran.pavlov.web.dom-test]
            [tech.thomascothran.pavlov.web.fetch-test]
            [tech.thomascothran.pavlov.web.server-test]
            [tech.thomascothran.pavlov.web.server.websocket-test]))

(defn main
  []
  (js/console.log "Pavlov Web Squint compatibility profile: experimental")
  (-> (test/run-tests
       'pavlov-web-example.browser-only.client-test
       'pavlov-web-example.client.main-test
       'pavlov-web-example.client.runtime-test
       'pavlov-web-example.game-of-life.client-test
       'tech.thomascothran.pavlov.web.client.websocket-connection-test
       'tech.thomascothran.pavlov.web.dom.scheduler-test
       'tech.thomascothran.pavlov.web.dom-test
       'tech.thomascothran.pavlov.web.fetch-test
       'tech.thomascothran.pavlov.web.server-test
       'tech.thomascothran.pavlov.web.server.websocket-test)
      js/Promise.resolve
      (.then (fn [summary]
               (js/process.exit (if (test/successful? summary) 0 1))))))

(-> (main)
    (.catch (fn [error]
              (js/console.error error)
              (set! (.-exitCode js/process) 1))))
