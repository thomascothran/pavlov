(ns tech.thomascothran.pavlov.web.dom.scheduler)

(defn- debounce-key
  [attr-prefix event-name]
  (keyword (str attr-prefix "-" event-name "-debounce-ms")))

(defn- throttle-key
  [attr-prefix event-name]
  (keyword (str attr-prefix "-" event-name "-throttle-ms")))

(defn- parse-ms
  [value]
  (when value
    #?(:clj (Long/parseLong value)
       :cljs (js/parseInt value 10))))

(defn- bucket-key
  [context]
  [(get context :dom/event-name)
   (get context :matched-el)])

(defn- submit-translated!
  [params]
  ((get params :submit!)
   ((get params :translator)
    (get params :native-event)
    (get params :context))))

(defn- scheduled-params
  [params token]
  (assoc (select-keys params [:native-event :context :translator :submit!])
         :token token))

(defn- shaping-ms
  [attr-prefix context]
  (let [event-name (get context :dom/event-name)
        debounce-ms (parse-ms (get context (debounce-key attr-prefix event-name)))
        throttle-ms (parse-ms (get context (throttle-key attr-prefix event-name)))]
    (when (and debounce-ms throttle-ms)
      (throw (ex-info "Debounce and throttle cannot both be set for one event"
                      {:event-name event-name
                       :debounce-ms debounce-ms
                       :throttle-ms throttle-ms})))
    {:debounce-ms debounce-ms
     :throttle-ms throttle-ms}))

(defn- default-set-timeout!
  []
  #?(:cljs js/setTimeout
     :clj (fn [_ _]
            (throw (ex-info "set-timeout! unavailable on clj"
                            {:fn `make-event-scheduler})))))

(defn- default-clear-timeout!
  []
  #?(:cljs js/clearTimeout
     :clj (fn [_]
            (throw (ex-info "clear-timeout! unavailable on clj"
                            {:fn `make-event-scheduler})))))

(defn- schedule-debounce!
  [{:keys [!debounces set-timeout! clear-timeout! bucket delay-ms]} params]
  (let [previous-token (get-in @!debounces [bucket :token])
        !token (volatile! nil)
        callback (fn []
                   (when-let [latest-params (get @!debounces bucket)]
                     (when (= (get latest-params :token) @!token)
                       (swap! !debounces dissoc bucket)
                       (submit-translated! latest-params))))
        token (set-timeout! callback delay-ms)]
    (when previous-token
      (clear-timeout! previous-token))
    (vreset! !token token)
    (swap! !debounces assoc bucket (scheduled-params params token))))

(defn- schedule-throttle!
  [{:keys [!throttles set-timeout! bucket delay-ms]} params]
  (when-not (contains? @!throttles bucket)
    (let [!token (volatile! nil)
          callback (fn []
                     (when (= (get @!throttles bucket) @!token)
                       (swap! !throttles dissoc bucket)))
          token (set-timeout! callback delay-ms)]
      (vreset! !token token)
      (swap! !throttles assoc bucket token)
      (submit-translated! params))))

(defn make-event-scheduler
  [opts]
  (let [!debounces (atom {})
        !throttles (atom {})
        set-timeout! (or (get opts :set-timeout!)
                         (default-set-timeout!))
        clear-timeout! (or (get opts :clear-timeout!)
                           (default-clear-timeout!))
        attr-prefix (get opts :attr-prefix "pavlov")]
    (fn [params]
      (let [context (get params :context)
            shaping (shaping-ms attr-prefix context)
            debounce-ms (get shaping :debounce-ms)
            throttle-ms (get shaping :throttle-ms)]
        (cond
          debounce-ms
          (schedule-debounce! {:!debounces !debounces
                               :set-timeout! set-timeout!
                               :clear-timeout! clear-timeout!
                               :bucket (bucket-key context)
                               :delay-ms debounce-ms}
                              params)

          throttle-ms
          (schedule-throttle! {:!throttles !throttles
                               :set-timeout! set-timeout!
                               :bucket (bucket-key context)
                               :delay-ms throttle-ms}
                              params)

          :else
          (submit-translated! params))))))
