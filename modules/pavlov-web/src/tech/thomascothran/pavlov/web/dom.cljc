(ns tech.thomascothran.pavlov.web.dom
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.web.dom.op :as dom-op]
            [tech.thomascothran.pavlov.web.dom.event-translators :as event-translators]
            [tech.thomascothran.pavlov.web.dom.scheduler :as scheduler]))

(def built-in-default-translators
  {"click" event-translators/default-click-translator
   "change" event-translators/default-input-translator
   "focusin" event-translators/default-focus-translator
   "focusout" event-translators/default-focus-translator
   "input" event-translators/default-input-translator
   "keydown" event-translators/default-keydown-translator
   "reset" event-translators/default-reset-translator
   "submit" event-translators/default-submit-translator
   "drag"  event-translators/default-drag-translator
   "dragenter" event-translators/default-dragenter-translator
   "dragstart" event-translators/default-dragstart-translator
   "dragover" event-translators/default-dragover-translator
   "dragend" event-translators/default-dragend-translator
   "dragleave" event-translators/default-dragleave-translator
   "drop" event-translators/default-drop-translator})

(defn make-dom-op-bthread
  ([] (make-dom-op-bthread #?(:cljs js/document.querySelectorAll
                              :clj (constantly nil))))
  ([query-selector]
   (b/on-any #{:pavlov.web.dom/op
               :pavlov.web.dom/ops}
             #(dom-op/-run-ops! query-selector %))))

(defn- copied-on-key
  "Find the copied `pavlov-on-*` attribute key that corresponds to a raw
  `:dom/...` event.

  Raw DOM events are first emitted with all copied `pavlov-*` attributes from
  the matched element. The redirect bthread uses this helper to map a raw event
  like `:dom/click` to the copied configuration key such as
  `:pavlov-on-click`, so it can request the semantic event named by that
  attribute."
  [incoming-event dom-event-type]
  (let [suffix (str "-on-" (name dom-event-type))]
    (some (fn [k]
            (when (and (keyword? k)
                       (.endsWith (name k) suffix))
              k))
          (keys incoming-event))))

(defn- built-in-raw-dom-event-types
  []
  (into #{} (map (comp keyword #(str "dom/" %))) (keys built-in-default-translators)))

(defn make-dom-event-redirect-bthread
  []
  (let [dom-event-types (built-in-raw-dom-event-types)]
    (b/step (fn [_state incoming-event]
              (let [dom-event-type (some-> incoming-event event/type)
                    raw-dom-event? (contains? dom-event-types dom-event-type)
                    pavlov-on-key (when raw-dom-event?
                                    (copied-on-key incoming-event dom-event-type))
                    pavlov-event-type (when pavlov-on-key
                                        (get incoming-event pavlov-on-key))]
                [::dom-event-redirect
                 (if (and incoming-event
                          raw-dom-event?
                          pavlov-event-type)
                   {:wait-on dom-event-types
                    :request #{(assoc incoming-event
                                      :type
                                      pavlov-event-type)}}
                   {:wait-on dom-event-types})])))))

(defn- attr-name-for-event
  [attr-prefix event-name]
  (str attr-prefix "-on-" event-name))

(defn- capture-selector-attr-name
  [attr-prefix]
  (str attr-prefix "-capture-selector"))

#?(:cljs
   (defn- copied-pavlov-attrs
     [attr-prefix element]
     (reduce (fn [acc attr]
               (let [attr-name (.-name attr)]
                 (if (.startsWith attr-name (str attr-prefix "-"))
                   (assoc acc (keyword attr-name) (.-value attr))
                   acc)))
             {}
             (array-seq (.-attributes element)))))

#?(:cljs
   (defn- child-dom-value
     [element]
     (let [tag-name (some-> (.-tagName element) (.toLowerCase))
           element-type (.-type element)]
       (cond-> {}
         (#{"input" "textarea" "select"} tag-name)
         (assoc :dom/value (.-value element))

         (#{"checkbox" "radio"} element-type)
         (assoc :dom/checked? (.-checked element))))))

#?(:cljs
   (defn- child-context
     [attr-prefix element]
     (merge {:id (.-id element)
             :tag (some-> (.-tagName element) (.toLowerCase))}
            (when-let [element-type (.-type element)]
              {:type element-type})
            (child-dom-value element)
            (copied-pavlov-attrs attr-prefix element))))

#?(:cljs
   (defn- captured-elements
     [attr-prefix root-node matched-el]
     (when-let [selector (.getAttribute matched-el (capture-selector-attr-name attr-prefix))]
       (->> (.querySelectorAll root-node selector)
            array-seq
            (mapv #(child-context attr-prefix %))))))

(defn default-resolve-from-attributes
  ([native-event]
   (default-resolve-from-attributes "pavlov" native-event))
  ([attr-prefix native-event]
   #?(:cljs
      (let [event-name (.-type native-event)
            attr-name (attr-name-for-event attr-prefix event-name)
            root (.-currentTarget native-event)
            target (.-target native-event)
            matched-el (when (and target (.-closest target))
                         (.closest target (str "[" attr-name "]")))]
        (when (and matched-el
                   (or (= matched-el root)
                       (.contains root matched-el)))
          (let [children (captured-elements attr-prefix root matched-el)]
            (cond-> (merge {:dom/event-name event-name
                            :matched-el matched-el
                            :attr-name attr-name
                            :attr-value (.getAttribute matched-el attr-name)}
                           (copied-pavlov-attrs attr-prefix matched-el))
              children
              (assoc :dom/children children)))))
      :clj nil)))

(def default-dom-opts
  {:events (set (keys built-in-default-translators))
   :attr-prefix "pavlov"
   :translators built-in-default-translators
   :prevent-default? {"submit" (constantly true)
                      "dragover" (constantly true)
                      "drop" (constantly true)}})

(defn attach-dom-events!
  "Capture events from a `root-node` and submit! them
  to a bprogram."
  [opts]
  (let [opts' (merge default-dom-opts opts)
        attr-prefix (get opts' :attr-prefix)
        submit! (or (get opts' :submit!)
                    (get opts' :submit))
        root-node (or (get opts' :root)
                      (get opts' :root-node)
                      #?(:cljs js/document
                         :clj nil))
        resolve (if (contains? opts :resolve)
                  (get opts :resolve)
                  (partial default-resolve-from-attributes attr-prefix))
        event-scheduler! (scheduler/make-event-scheduler
                          {:set-timeout! (get opts' :set-timeout!)
                           :clear-timeout! (get opts' :clear-timeout!)
                           :attr-prefix attr-prefix})
        translators (merge (get default-dom-opts :translators)
                           (get opts' :translators))]
    #?(:cljs
       (doseq [event-name (get opts' :events)]
         (.addEventListener
          root-node
          event-name
          (fn [native-event]
            (when-let [context (resolve native-event)]
              (when-let [translator (get translators event-name)]
                (let [dom-event-type (get context :dom/event-name)
                      prevent-default? (get-in opts' [:prevent-default? dom-event-type])]
                  (when (and prevent-default?
                             (prevent-default? native-event))
                    (.preventDefault native-event))
                  (event-scheduler! {:native-event native-event
                                     :context context
                                     :translator translator
                                     :submit! submit!}))))))))))
