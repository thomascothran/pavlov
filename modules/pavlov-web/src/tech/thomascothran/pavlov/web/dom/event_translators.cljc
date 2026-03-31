(ns tech.thomascothran.pavlov.web.dom.event-translators)

(defn- element-identity
  [element]
  {:id (.-id element)
   :tag (.toLowerCase (.-tagName element))
   :type (when-let [element-type (.-type element)]
           element-type)})

(defn- raw-dom-type
  [context]
  (keyword "dom" (get context :dom/event-name)))

(defn- attr-prefix
  [context]
  (let [attr-name (get context :attr-name)
        event-name (get context :dom/event-name)
        suffix (when event-name
                 (str "-on-" event-name))]
    (or (when (and attr-name suffix (.endsWith attr-name suffix))
          (subs attr-name 0 (- (count attr-name) (count suffix))))
        "pavlov")))

(defn- raw-dom-context
  [context]
  (let [attr-prefix' (attr-prefix context)
        on-prefix (when attr-prefix'
                    (str attr-prefix' "-on-"))]
    (reduce-kv (fn [acc k v]
                 (if (or (= k :dom/event-name)
                         (= k :dom/children)
                         (and attr-prefix'
                              (.startsWith (name k) (str attr-prefix' "-"))))
                   (assoc acc
                          k
                          (if (and (keyword? k)
                                   on-prefix
                                   (.startsWith (name k) on-prefix)
                                   (string? v)
                                   (.startsWith v ":")
                                   (< 1 (count v)))
                            (keyword (subs v 1))
                            v))
                   acc))
               {}
               context)))

(defn- serialize-form-values
  [form excluded-control-type]
  #?(:cljs
     (reduce (fn [acc element]
               (let [name (.-name element)
                     element-type (.-type element)]
                 (if (and (seq name)
                          (not= excluded-control-type element-type))
                   (assoc acc name (.-value element))
                   acc)))
             {}
             (array-seq (.-elements form)))
     :clj
     (throw (ex-info "serialize-form-values is only available in cljs"
                     {:translator `serialize-form-values}))))

(defn- submitter-identity
  [native-event]
  #?(:cljs
     (when-let [submitter (.-submitter native-event)]
       {:id (.-id submitter)
        :name (.-name submitter)
        :value (.-value submitter)})
     :clj
     (throw (ex-info "submitter-identity is only available in cljs"
                     {:translator `submitter-identity}))))

(defn default-input-translator
  [native-event context]
  #?(:cljs
     (let [target (.-target native-event)
           target-type (.-type target)
           input-payload {:name (.-name target)
                          :value (.-value target)
                          :target (element-identity target)}
           input-payload (if (#{"checkbox" "radio"} target-type)
                           (assoc input-payload :checked? (.-checked target))
                           input-payload)]
       (assoc (raw-dom-context context)
              :type (raw-dom-type context)
              :dom/input input-payload))
     :clj
     (throw (ex-info "default-input-translator is only available in cljs"
                     {:translator `default-input-translator}))))

(defn default-submit-translator
  [native-event context]
  #?(:cljs
     (let [form (.-target native-event)
           values (serialize-form-values form "submit")
           submitter (submitter-identity native-event)]
       (assoc (raw-dom-context context)
              :type (raw-dom-type context)
              :dom/form (cond-> {:values values}
                          submitter (assoc :submitter submitter))))
     :clj
     (throw (ex-info "default-submit-translator is only available in cljs"
                     {:translator `default-submit-translator}))))

(defn default-reset-translator
  [native-event context]
  #?(:cljs
     (let [form (.-target native-event)
           values (serialize-form-values form "reset")]
       (assoc (raw-dom-context context)
              :type (raw-dom-type context)
              :dom/form {:values values}))
     :clj
     (throw (ex-info "default-reset-translator is only available in cljs"
                     {:translator `default-reset-translator}))))

(defn default-click-translator
  [native-event context]
  #?(:cljs
     (let [target (.-target native-event)
           matched-el (:matched-el context)]
       (assoc (raw-dom-context context)
              :type (raw-dom-type context)
              :dom/target (element-identity target)
              :dom/matched (select-keys (element-identity matched-el) [:id :tag])))
     :clj
     (throw (ex-info "default-click-translator is only available in cljs"
                     {:translator `default-click-translator}))))

(defn default-focus-translator
  [native-event context]
  #?(:cljs
     (let [target (.-target native-event)
           matched-el (:matched-el context)
           related-target (.-relatedTarget native-event)]
       (cond-> (assoc (raw-dom-context context)
                      :type (raw-dom-type context)
                      :dom/target (element-identity target)
                      :dom/matched (select-keys (element-identity matched-el) [:id :tag]))
         related-target
         (assoc :dom/related-target (element-identity related-target))))
     :clj
     (throw (ex-info "default-focus-translator is only available in cljs"
                     {:translator `default-focus-translator}))))

(defn default-keydown-translator
  [native-event context]
  #?(:cljs
     (let [target (.-target native-event)
           matched-el (:matched-el context)]
       (assoc (raw-dom-context context)
              :type (raw-dom-type context)
              :dom/target (element-identity target)
              :dom/matched (select-keys (element-identity matched-el) [:id :tag])
              :dom/key {:key (.-key native-event)
                        :code (.-code native-event)
                        :alt? (.-altKey native-event)
                        :ctrl? (.-ctrlKey native-event)
                        :meta? (.-metaKey native-event)
                        :shift? (.-shiftKey native-event)
                        :repeat? (.-repeat native-event)}))
     :clj
     (throw (ex-info "default-keydown-translator is only available in cljs"
                     {:translator `default-keydown-translator}))))

(defn- data-transfer-types
  [data-transfer]
  #?(:cljs
     (when-let [types (some-> data-transfer .-types array-seq seq)]
       (vec types))
     :clj
     (throw (ex-info "data-transfer-types is only available in cljs"
                     {:translator `data-transfer-types}))))

(defn- data-transfer-items
  [data-transfer]
  #?(:cljs
     (when-let [items (some-> data-transfer .-items array-seq seq)]
       (mapv (fn [item]
               {:kind (.-kind item)
                :type (.-type item)})
             items))
     :clj
     (throw (ex-info "data-transfer-items is only available in cljs"
                     {:translator `data-transfer-items}))))

(defn- drag-payload
  [native-event]
  (let [data-transfer (.-dataTransfer native-event)
        types (data-transfer-types data-transfer)
        items (data-transfer-items data-transfer)]
    (cond-> {:client-x (.-clientX native-event)
             :client-y (.-clientY native-event)
             :alt? (.-altKey native-event)
             :ctrl? (.-ctrlKey native-event)
             :meta? (.-metaKey native-event)
             :shift? (.-shiftKey native-event)}
      data-transfer
      (assoc :drop-effect (.-dropEffect data-transfer)
             :effect-allowed (.-effectAllowed data-transfer))

      types
      (assoc :types types)

      items
      (assoc :items items))))

(defn- default-draglike-translator
  [native-event context]
  (let [target (.-target native-event)
        matched-el (:matched-el context)]
    (assoc (raw-dom-context context)
           :type (raw-dom-type context)
           :dom/target (element-identity target)
           :dom/matched (select-keys (element-identity matched-el) [:id :tag])
           :dom/drag (drag-payload native-event))))

(defn default-drag-translator
  [native-event context]
  #?(:cljs
     (default-draglike-translator native-event context)
     :clj
     (throw (ex-info "default-drag-translator is only available in cljs"
                     {:translator `default-drag-translator}))))

(defn default-dragenter-translator
  [native-event context]
  #?(:cljs
     (default-draglike-translator native-event context)
     :clj
     (throw (ex-info "default-dragenter-translator is only available in cljs"
                     {:translator `default-dragenter-translator}))))

(defn default-dragstart-translator
  [native-event context]
  #?(:cljs
     (default-draglike-translator native-event context)
     :clj
     (throw (ex-info "default-dragstart-translator is only available in cljs"
                     {:translator `default-dragstart-translator}))))

(defn default-dragover-translator
  [native-event context]
  #?(:cljs
     (default-draglike-translator native-event context)
     :clj
     (throw (ex-info "default-dragover-translator is only available in cljs"
                     {:translator `default-dragover-translator}))))

(defn default-dragend-translator
  [native-event context]
  #?(:cljs
     (default-draglike-translator native-event context)
     :clj
     (throw (ex-info "default-dragend-translator is only available in cljs"
                     {:translator `default-dragend-translator}))))

(defn default-dragleave-translator
  [native-event context]
  #?(:cljs
     (default-draglike-translator native-event context)
     :clj
     (throw (ex-info "default-dragleave-translator is only available in cljs"
                     {:translator `default-dragleave-translator}))))

(defn default-drop-translator
  [native-event context]
  #?(:cljs
     (default-draglike-translator native-event context)
     :clj
     (throw (ex-info "default-drop-translator is only available in cljs"
                     {:translator `default-drop-translator}))))
