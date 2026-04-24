(ns pavlov-web-example.game-of-life.websocket
  (:require [clojure.edn :as edn]
            [pavlov-web-example.game-of-life.board :as board]
            [pavlov-web-example.game-of-life.client-manager :as client-manager]
            [pavlov-web-example.game-of-life.config :as config]
            [pavlov-web-example.game-of-life.dom-ops :as dom-ops]
            [pavlov-web-example.game-of-life.game-status :as game-status]
            [ring.websocket]
            [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.web.server :as server]
            [tech.thomascothran.pavlov.web.server.ring-websocket :as ring-websocket])
  (:import (java.util.concurrent Executors TimeUnit)))

(defn- log
  [& args]
  (apply println "[game-of-life.websocket]" args))

(defn- warn
  [& args]
  (binding [*out* *err*]
    (apply println "[game-of-life.websocket]" args)))

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

(defn- runtime-key [{::keys [runtime-id start-clock? send-websocket!]}]
  [(or send-websocket! ring.websocket/send)
   (or runtime-id default-runtime-id)
   (not= false start-clock?)])

(defn- notify-bridge!
  [bridge event]
  (when bridge
    (b/notify! bridge event)))

(def ^:private send-failed-event-type
  :game-of-life/client-send-failed)

(defn- send-failure-event
  [bid]
  (some (fn [event]
          (when (= send-failed-event-type (:type event))
            event))
        (:request bid)))

(defn- send-via-bridge!
  [bridge event]
  (notify-bridge! bridge {:type :pavlov.web.server/send-event
                          :event event
                          :on-error-event-type send-failed-event-type}))

(defn- remove-stale-client!
  [{:keys [!clients !program]} client-id]
  (when (contains? @!clients client-id)
    (log "removing stale client-id=" client-id
         " clients=" (count @!clients))
    (swap! !clients dissoc client-id)
    (when-let [program @!program]
      (bp/submit-event! program {:type :game-of-life/client-closed
                                 :client-id client-id}))))

(defn- handle-send-result!
  [runtime client-id bid]
  (when (send-failure-event bid)
    (remove-stale-client! runtime client-id)))

(defn- make-dispatch-subscriber
  [{:keys [get-client-bridge !clients] :as runtime}]
  (fn [selected-event _program]
    (case (:type selected-event)
      :game-of-life/broadcast
      (do
        (log "broadcast event-type=" (get-in selected-event [:event :type])
             " clients=" (count @!clients))
        (doseq [[client-id client] @!clients]
          (handle-send-result! runtime client-id
                               (send-via-bridge! (:bridge client) (:event selected-event)))))

      :game-of-life/send-to-client
      (do
        (log "send-to-client client-id=" (:client-id selected-event)
             " event-type=" (get-in selected-event [:event :type])
             " connected=" (boolean (get-client-bridge (:client-id selected-event))))
        (when-let [bridge (get-client-bridge (:client-id selected-event))]
          (handle-send-result! runtime (:client-id selected-event)
                               (send-via-bridge! bridge (:event selected-event)))))

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
        !program (atom nil)
        runtime {:!clients !clients
                 :!program !program
                 :get-client-bridge (fn [client-id]
                                      (get-in @!clients [client-id :bridge]))}
        program (bpe/make-program! (make-bthreads)
                                   {:subscribers {:dispatch
                                                  (make-dispatch-subscriber runtime)}})
        clock (when (not= false start-clock?)
                (start-clock! program))]
    (reset! !program program)
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

(defn- wrap-listener
  [listener !client-id !bridge]
  (assoc listener
         :on-open (fn [websocket]
                    (reset! !client-id (new-client-id websocket))
                    ((:on-open listener) websocket))
         :on-close (fn [websocket status-code reason]
                     ((:on-close listener) websocket status-code reason)
                     (reset! !bridge nil)
                     (reset! !client-id nil))))

(defn- make-submit-event!
  [{:keys [!bridge !client-id !clients program]}]
  (fn [event]
    (notify-bridge! @!bridge event)
    (let [client-id @!client-id]
      (case (:type event)
        :pavlov.web.server/connected
        (if client-id
          (do
            (log "on-open client-id=" client-id
                 " clients=" (count @!clients))
            (swap! !clients assoc client-id {:bridge @!bridge})
            (bp/submit-event! program {:type :game-of-life/client-opened
                                       :client-id client-id}))
          (warn "connected without client-id"))

        :pavlov.web.server/event-received
        (let [semantic-event (cond-> (:event event)
                               client-id (assoc :client-id client-id))]
          (log "on-message client-id=" client-id
               " event-type=" (:type semantic-event)
               " payload=" (pr-str (:event event)))
          (bp/submit-event! program semantic-event))

        :pavlov.web.server/disconnected
        (if client-id
          (do
            (log "on-close client-id=" client-id
                 " clients=" (count @!clients))
            (swap! !clients dissoc client-id)
            (bp/submit-event! program {:type :game-of-life/client-closed
                                       :client-id client-id}))
          (warn "disconnected without client-id"))

        nil))))

(defn handler
  [request]
  (let [send-websocket! ring.websocket/send
        runtime-opts (assoc (select-keys request [runtime-id-key start-clock?-key])
                            ::send-websocket! send-websocket!)
        {:keys [!clients program]} (ensure-runtime! runtime-opts)
        !client-id (atom nil)
        !bridge (atom nil)
        submit-event! (make-submit-event! {:!bridge !bridge
                                           :!client-id !client-id
                                           :!clients !clients
                                           :program program})
        ring-adapter (ring-websocket/make-ring-websocket-adapter
                      {:submit-event! submit-event!
                       :send-websocket! (fn [websocket payload]
                                          (try
                                            (send-websocket! websocket payload)
                                            (catch Throwable error
                                              (warn "send failed websocket-id=" (:websocket/id websocket)
                                                    " error=" (.getMessage error))
                                              (throw error))))
                       :encode pr-str
                       :decode edn/read-string})
        listener (wrap-listener (:listener ring-adapter) !client-id !bridge)]
    (log "handler request runtime-key=" (runtime-key runtime-opts)
         " clients=" (count @!clients))
    (reset! !bridge (server/make-server-bridge-bthread submit-event!
                                                       (:bridge-opts ring-adapter)))
    (b/notify! @!bridge nil)
    {:ring.websocket/listener listener}))
