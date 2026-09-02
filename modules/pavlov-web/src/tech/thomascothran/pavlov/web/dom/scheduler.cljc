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

(defn- make-bucket-store
  []
  #?(:cljs (js/Map.)
     :clj (atom {})))

(defn- bucket-value
  [store element event-name]
  #?(:cljs (when-let [event->value (.get store element)]
             (.get event->value event-name))
     :clj (get @store [element event-name])))

(defn- bucket-contains?
  [store element event-name]
  #?(:cljs (if-let [event->value (.get store element)]
             (.has event->value event-name)
             false)
     :clj (contains? @store [element event-name])))

(defn- put-bucket!
  [store element event-name value]
  #?(:cljs (let [event->value (or (.get store element) (js/Map.))]
             (.set event->value event-name value)
             (.set store element event->value))
     :clj (swap! store assoc [element event-name] value))
  value)

(defn- remove-bucket!
  [store element event-name]
  #?(:cljs (when-let [event->value (.get store element)]
             (.delete event->value event-name)
             (when (zero? (.-size event->value))
               (.delete store element)))
     :clj (swap! store dissoc [element event-name])))

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
  [{:keys [store set-timeout! clear-timeout! element event-name delay-ms]} params]
  (let [previous-token (get (bucket-value store element event-name) :token)
        !token (volatile! nil)
        callback (fn []
                   (when-let [latest-params (bucket-value store element event-name)]
                     (when (= (get latest-params :token) @!token)
                       (remove-bucket! store element event-name)
                       (submit-translated! latest-params))))
        token (set-timeout! callback delay-ms)]
    (when previous-token
      (clear-timeout! previous-token))
    (vreset! !token token)
    (put-bucket! store element event-name (scheduled-params params token))))

(defn- schedule-throttle!
  [{:keys [store set-timeout! element event-name delay-ms]} params]
  (when-not (bucket-contains? store element event-name)
    (let [!token (volatile! nil)
          callback (fn []
                     (when (= (bucket-value store element event-name) @!token)
                       (remove-bucket! store element event-name)))
          token (set-timeout! callback delay-ms)]
      (vreset! !token token)
      (put-bucket! store element event-name token)
      (submit-translated! params))))

(defn make-event-scheduler
  [opts]
  (let [debounces (make-bucket-store)
        throttles (make-bucket-store)
        set-timeout! (or (get opts :set-timeout!)
                         (default-set-timeout!))
        clear-timeout! (or (get opts :clear-timeout!)
                           (default-clear-timeout!))
        attr-prefix (get opts :attr-prefix "pavlov")]
    (fn [params]
      (let [context (get params :context)
            event-name (get context :dom/event-name)
            element (get context :matched-el)
            shaping (shaping-ms attr-prefix context)
            debounce-ms (get shaping :debounce-ms)
            throttle-ms (get shaping :throttle-ms)]
        (cond
          debounce-ms
          (schedule-debounce! {:store debounces
                               :set-timeout! set-timeout!
                               :clear-timeout! clear-timeout!
                               :element element
                               :event-name event-name
                               :delay-ms debounce-ms}
                              params)

          throttle-ms
          (schedule-throttle! {:store throttles
                               :set-timeout! set-timeout!
                               :element element
                               :event-name event-name
                               :delay-ms throttle-ms}
                              params)

          :else
          (submit-translated! params))))))
