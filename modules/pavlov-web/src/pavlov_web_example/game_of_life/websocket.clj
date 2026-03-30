(ns pavlov-web-example.game-of-life.websocket
  (:require [clojure.edn :as edn]
            [pavlov-web-example.game-of-life.board :as board]
            [pavlov-web-example.game-of-life.client-manager :as client-manager]
            [pavlov-web-example.game-of-life.config :as config]
            [pavlov-web-example.game-of-life.dom-ops :as dom-ops]
            [pavlov-web-example.game-of-life.game-status :as game-status]
            [ring.websocket]
            [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe])
  (:import (java.util.concurrent Executors TimeUnit)))

(def ^:private !shared-runtimes (atom {}))

(def ^:private default-runtime-id ::default)

(defn- new-client-id
  [websocket]
  (or (:websocket/id websocket)
      (str (random-uuid))))

(def ^:private runtime-id-key ::runtime-id)
(def ^:private start-clock?-key ::start-clock?)

(defn make-bthreads
  []
  [[:client-manager (client-manager/make-client-manager-bthread)]
   [:game-status (game-status/make-game-status-bthread)]
   [:board (board/make-board-bthread config/board-height
                                     config/board-width)]
   [:dom-ops (dom-ops/make-dom-ops-bthread)]])

(defn- send-event!
  [websocket event]
  (ring.websocket/send websocket (pr-str event)))

(defn- runtime-key [{::keys [runtime-id start-clock?]}]
  [ring.websocket/send
   (or runtime-id default-runtime-id)
   (not= false start-clock?)])

(defn- make-dispatch-subscriber
  [{:keys [get-websocket-by-id !clients]}]
  (fn [selected-event _program]
    (case (:type selected-event)
      :game-of-life/broadcast
      (doseq [websocket (vals @!clients)]
        (send-event! websocket (:event selected-event)))

      :game-of-life/send-to-client
      (when-let [websocket (get-websocket-by-id (:client-id selected-event))]
        (send-event! websocket (:event selected-event)))

      nil)))

(defn- start-clock!
  [program]
  (let [executor (Executors/newSingleThreadScheduledExecutor)
        task (reify Runnable
               (run [_]
                 (bp/submit-event! program {:type :game-of-life/tick})))
        future (.scheduleWithFixedDelay executor
                                        task
                                        config/tick-interval-ms
                                        config/tick-interval-ms
                                        TimeUnit/MILLISECONDS)]
    {:executor executor
     :future future}))

(defn- make-runtime
  [{::keys [start-clock?]}]
  (let [!clients (atom {})
        !client-ids (atom {})
        runtime {:!clients !clients
                 :!client-ids !client-ids
                 :get-websocket-by-id (fn [client-id]
                                        (get @!clients client-id))}
        program (bpe/make-program! (make-bthreads)
                                   {:subscribers {:dispatch
                                                  (make-dispatch-subscriber runtime)}})
        clock (when (not= false start-clock?)
                (start-clock! program))]
    (assoc runtime
           :clock clock
           :program program)))

(defn- ensure-runtime!
  [runtime-opts]
  (let [k (runtime-key runtime-opts)]
    (or (get @!shared-runtimes k)
        (get (swap! !shared-runtimes
                    #(if (contains? % k)
                       %
                       (assoc % k (make-runtime runtime-opts))))
             k))))

(defn handler
  [request]
  (let [runtime-opts (select-keys request [runtime-id-key start-clock?-key])
        {:keys [!clients !client-ids program] :as runtime} (ensure-runtime! runtime-opts)]
    {:ring.websocket/listener
     {:on-open (fn [websocket]
                 (let [client-id (new-client-id websocket)]
                   (swap! !clients assoc client-id websocket)
                   (swap! !client-ids assoc websocket client-id)
                   (bp/submit-event! program {:type :game-of-life/client-opened
                                              :client-id client-id})))
      :on-message (fn [_websocket raw-payload]
                    (bp/submit-event! program (edn/read-string raw-payload)))
      :on-close (fn [websocket _status-code _reason]
                  (when-let [client-id (get @!client-ids websocket)]
                    (swap! !client-ids dissoc websocket)
                    (swap! !clients dissoc client-id)
                    (bp/submit-event! program {:type :game-of-life/client-closed
                                               :client-id client-id})))}}))
