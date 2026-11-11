(ns ^:alpha tech.thomascothran.pavlov.explorer
  "A browser tool for walking a behavioral program one event at a time.

  Run it in the same JVM as the programs it offers:

  ```clojure
  (require '[tech.thomascothran.pavlov.explorer :as explorer])

  (def catalogue
    [{:id :account-opening
      :name \"Account opening\"
      :description \"Identity and sanctions checks, then the first deposit.\"
      :make-config #'make-check-config
      :sources {:open-on-funding #'make-open-on-funding-bthread}}])

  (explorer/start! {:catalogue #'catalogue :port 4990})
  ```

  `:make-config` returns the config map you would give to
  `tech.thomascothran.pavlov.model.check/check`. See
  `tech.thomascothran.pavlov.explorer.program` for the catalogue entry keys.

  A program is explored the first time someone opens it and kept until it is
  reloaded, with the page's Reload button or with `reload!`. The programs must
  not do anything to the outside world, as for model checking."
  (:require [clojure.string :as str]
            [reitit.ring :as ring]
            [ring.adapter.jetty9 :as jetty]
            [ring.middleware.params :as params]
            [tech.thomascothran.pavlov.explorer.page :as page]
            [tech.thomascothran.pavlov.explorer.position :as position]
            [tech.thomascothran.pavlov.explorer.program :as program]))

(defn- entries
  "The catalogue as it is now. It may be a collection of entries, a var
  holding one, or a function returning one."
  [catalogue]
  (cond
    (var? catalogue) (entries @catalogue)
    (fn? catalogue) (catalogue)
    :else catalogue))

(defn- find-entry
  [catalogue id]
  (first (filter #(= id (program/program-id %)) (entries catalogue))))

(defn- built-program
  "The explored program for `entry`, building it if it is not cached."
  [!programs entry]
  (let [id (program/program-id entry)]
    (or (get @!programs id)
        (let [built (program/build entry)]
          (swap! !programs assoc id built)
          built))))

(defn- html
  [status body]
  {:status status
   :headers {"content-type" "text/html; charset=utf-8"}
   :body body})

(defn- split-trace
  [s]
  (if (str/blank? s) [] (str/split s #"\.")))

(defn- opts-of
  [request]
  (when (get-in request [:params "bthreads"]) {:bthreads? true}))

(defn- unknown-program
  [catalogue id]
  (html 404 (page/catalogue
             (entries catalogue)
             (str "The program \"" id "\" is not available. Choose one of the programs below."))))

(defn- program-page
  [{:keys [catalogue !programs]} request]
  (let [{:keys [id trace]} (:path-params request)]
    (if-let [entry (find-entry catalogue id)]
      (let [built (try (built-program !programs entry)
                       (catch Exception e e))]
        (if (instance? Exception built)
          (html 500 (page/failed entry (or (ex-message built) (str (class built)))))
          (html 200 (page/program built
                                  (position/locate built (split-trace trace))
                                  (opts-of request)))))
      (unknown-program catalogue id))))

(defn- reload-program
  [{:keys [catalogue !programs]} request]
  (let [id (get-in request [:path-params :id])]
    (if (find-entry catalogue id)
      (do (swap! !programs dissoc id)
          {:status 303
           :headers {"location" (page/href id
                                           (split-trace (get-in request [:params "trace"]))
                                           (opts-of request))}})
      (unknown-program catalogue id))))

(defn make-handler
  "Ring handler for the explorer.

  `catalogue` is a collection of catalogue entries, a var holding one, or a
  function returning one. `!programs` is an atom caching explored programs by
  id."
  [{:keys [catalogue] :as system}]
  (-> (ring/ring-handler
       (ring/router
        [["/" {:get (fn [_] (html 200 (page/catalogue (entries catalogue) nil)))}]
         ["/p/:id" {:get #(program-page system %)}]
         ["/p/:id/reload" {:post #(reload-program system %)}]
         ["/p/:id/:trace" {:get #(program-page system %)}]
         ["/assets/*" (ring/create-resource-handler
                       {:root "tech/thomascothran/pavlov/explorer/public"})]]
        {:conflicts nil})
       (ring/create-default-handler))
      params/wrap-params))

(defonce ^:private !running (atom nil))

(defn stop!
  "Stop the explorer started by `start!`."
  []
  (when-let [{:keys [server]} @!running]
    (.stop server)
    (reset! !running nil))
  :stopped)

(defn start!
  "Start the explorer and return its address.

  Options:
    :catalogue - collection of catalogue entries, a var holding one, or a
                 function returning one. A var or function is read on every
                 request, so entries can be added without restarting.
    :port      - default 4990"
  [{:keys [catalogue port] :or {port 4990}}]
  (stop!)
  (let [!programs (atom {})]
    (reset! !running
            {:!programs !programs
             :server (jetty/run-jetty (make-handler {:catalogue catalogue
                                                     :!programs !programs})
                                      {:port port :join? false})})
    (str "http://localhost:" port "/")))

(defn reload!
  "Forget explored programs so they are rebuilt from their current code the
  next time they are opened. With no id, forgets all of them."
  ([] (some-> @!running :!programs (reset! {})) :reloaded)
  ([id]
   (some-> @!running :!programs (swap! dissoc (program/program-id {:id id})))
   :reloaded))
