(ns tech.thomascothran.pavlov.web.dom
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.web.dom.event-translators :as event-translators]))

#?(:cljs
   (defn- set-member!
     [dom-node member value]
     (unchecked-set dom-node member value)))

#?(:cljs
   (defn- call-member!
     [dom-node member args]
     (.apply (aget dom-node member) dom-node (into-array args))))

(defn -run-op!
  [query-selector event]
  (let [selector (get event :selector)
        kind (get event :kind)
        member (get event :member)
        args (when (= :call kind)
               (get event :args))
        value (when (= :set kind)
                (get event :value))
        dom-nodes #?(:cljs (query-selector selector)
                     :clj nil)]
    (doseq [dom-node dom-nodes]
      #?(:cljs
         (case kind
           :set (set-member! dom-node member value)
           :call (call-member! dom-node member args)
           nil)))))

(defn make-dom-op-bthread
  ([] (make-dom-op-bthread #?(:cljs js/document.querySelectorAll
                              :clj (constantly nil))))
  ([query-selector]
   (b/on :pavlov.web.dom/op #(-run-op! query-selector %))))

(defn- attr-name-for-event
  [event-name]
  (str "data-pavlov-on-" event-name))

(defn default-resolve-from-attributes
  [native-event]
  #?(:cljs
     (let [event-name (.-type native-event)
           attr-name (attr-name-for-event event-name)
           root (.-currentTarget native-event)
           target (.-target native-event)
           matched-el (when (and target (.-closest target))
                        (.closest target (str "[" attr-name "]")))]
       (when (and matched-el
                  (or (= matched-el root)
                      (.contains root matched-el)))
         {:dom/event-name event-name
          :matched-el matched-el
          :attr-name attr-name
          :attr-value (.getAttribute matched-el attr-name)}))
     :clj nil))

(def built-in-default-translators
  {"click" event-translators/default-click-translator
   "change" event-translators/default-input-translator
   "focusin" event-translators/default-focus-translator
   "focusout" event-translators/default-focus-translator
   "input" event-translators/default-input-translator
   "keydown" event-translators/default-keydown-translator
   "reset" event-translators/default-reset-translator
   "submit" event-translators/default-submit-translator})

(def default-dom-opts
  {:events (set (keys built-in-default-translators))
   :resolve default-resolve-from-attributes
   :translators built-in-default-translators})

(defn attach-dom-events!
  "Capture events from a `root-node` and submit! them
  to a bprogram."
  [opts]
  (let [opts' (merge default-dom-opts opts)
        submit! (or (get opts' :submit!)
                    (get opts' :submit))
        root-node (or (get opts' :root)
                      (get opts' :root-node)
                      #?(:cljs js/document
                         :clj nil))
        resolve (get opts' :resolve)
        translators (merge (get default-dom-opts :translators)
                           (get opts' :translators))]
    (assert (fn? submit!))
    (assert (fn? resolve))
    #?(:cljs
       (doseq [event-name (get opts' :events)]
         (.addEventListener
          root-node
          event-name
          (fn [native-event]
            (when-let [context (resolve native-event)]
              (when-let [translator (get translators event-name)]
                (submit! (translator native-event context))))))))))
