(ns tech.thomascothran.pavlov.web.dom.event-translators)

(defn- element-identity
  [element]
  {:id (.-id element)
   :tag (.toLowerCase (.-tagName element))
   :type (when-let [element-type (.-type element)]
           element-type)})

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
       {:type (keyword (subs (:attr-value context) 1))
        :dom/event-name (:dom/event-name context)
        :dom/input input-payload})
     :clj
     (throw (ex-info "default-input-translator is only available in cljs"
                     {:translator `default-input-translator}))))

(defn default-submit-translator
  [native-event context]
  #?(:cljs
      (let [form (.-target native-event)
            values (serialize-form-values form "submit")
            submitter (submitter-identity native-event)]
        {:type (keyword (subs (:attr-value context) 1))
         :dom/event-name (:dom/event-name context)
         :dom/form (cond-> {:values values}
                     submitter (assoc :submitter submitter))})
         :clj
         (throw (ex-info "default-submit-translator is only available in cljs"
                        {:translator `default-submit-translator}))))

(defn default-reset-translator
  [native-event context]
  #?(:cljs
      (let [form (.-target native-event)
            values (serialize-form-values form "reset")]
        {:type (keyword (subs (:attr-value context) 1))
         :dom/event-name (:dom/event-name context)
         :dom/form {:values values}})
      :clj
      (throw (ex-info "default-reset-translator is only available in cljs"
                     {:translator `default-reset-translator}))))

(defn default-click-translator
  [native-event context]
  #?(:cljs
     (let [target (.-target native-event)
            matched-el (:matched-el context)]
        {:type (keyword (subs (:attr-value context) 1))
         :dom/event-name (:dom/event-name context)
         :dom/target (element-identity target)
         :dom/matched (select-keys (element-identity matched-el) [:id :tag])})
     :clj
     (throw (ex-info "default-click-translator is only available in cljs"
                     {:translator `default-click-translator}))))

(defn default-focus-translator
  [native-event context]
  #?(:cljs
     (let [target (.-target native-event)
            matched-el (:matched-el context)
           related-target (.-relatedTarget native-event)]
       (cond-> {:type (keyword (subs (:attr-value context) 1))
                :dom/event-name (:dom/event-name context)
                :dom/target (element-identity target)
                :dom/matched (select-keys (element-identity matched-el) [:id :tag])}
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
       {:type (keyword (subs (:attr-value context) 1))
        :dom/event-name (:dom/event-name context)
        :dom/target (element-identity target)
        :dom/matched (select-keys (element-identity matched-el) [:id :tag])
        :dom/key {:key (.-key native-event)
                  :code (.-code native-event)
                  :alt? (.-altKey native-event)
                  :ctrl? (.-ctrlKey native-event)
                  :meta? (.-metaKey native-event)
                  :shift? (.-shiftKey native-event)
                  :repeat? (.-repeat native-event)}})
     :clj
     (throw (ex-info "default-keydown-translator is only available in cljs"
                     {:translator `default-keydown-translator}))))
