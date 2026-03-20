(ns tech.thomascothran.pavlov.web.dom-test
  (:require [cljs.test :refer-macros [deftest is]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.web.dom :as dom]
            ["jsdom" :refer [JSDOM]]))

(deftest dom-namespace-loads
  (is true))

(deftest make-dom-op-bthread-sets-input-value-property
  (let [document (-> (JSDOM. "<input id=\"search\" value=\"before\">")
                     (.-window)
                     (.-document))
        input (.querySelector document "#search")
        bthread (dom/make-dom-op-bthread #(.querySelectorAll document %))]
    (b/notify! bthread nil)
    (b/notify! bthread {:type :pavlov.web.dom/op
                        :selector "#search"
                        :kind :set
                        :member "value"
                        :value "after"})
    (is (= "after" (.-value input)))))

(deftest make-dom-op-bthread-calls-dom-method
  (let [document (-> (JSDOM. "<input id=\"search\">")
                     (.-window)
                     (.-document))
        input (.querySelector document "#search")
        bthread (dom/make-dom-op-bthread #(.querySelectorAll document %))]
    (b/notify! bthread nil)
    (b/notify! bthread {:type :pavlov.web.dom/op
                        :selector "#search"
                        :kind :call
                        :member "setAttribute"
                        :args ["data-state" "ready"]})
    (is (= "ready" (.getAttribute input "data-state")))))

(deftest attach-dom-events-delegates-configured-click-through-resolve-translator-and-submit
  (let [dom (JSDOM. "<div id=\"root\"><button id=\"save\">Save</button></div>")
        document (-> dom (.-window) (.-document))
        root (.querySelector document "#root")
        button (.querySelector document "#save")
        native-event (.createEvent document "Event")
        !calls (atom [])
        !submitted-events (atom [])
        resolved-context {:dom/event-name "click"
                          :matched-el button
                          :attr-name "data-pavlov-on-click"
                          :attr-value ":task/save"}
        resolve (fn [event]
                  (swap! !calls conj [:resolve event])
                  resolved-context)
        translator (fn [event context]
                     (swap! !calls conj [:translate event context])
                     {:type :task/save-clicked
                      :dom/id (.getAttribute button "id")})
        submit! (fn [event]
                  (swap! !calls conj [:submit event])
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (.initEvent native-event "click" true true)
    (dom/attach-dom-events! {:root root
                             :root-node root
                             :events ["click"]
                             :resolve resolve
                             :translators {"click" translator}
                             :submit submit!
                             :submit! submit!})
    (.dispatchEvent button native-event)
    (is (= [[:resolve native-event]
            [:translate native-event resolved-context]
            [:submit {:type :task/save-clicked
                      :dom/id "save"}]]
           @!calls))
    (is (= [{:type :task/save-clicked
             :dom/id "save"}]
           @!submitted-events))))

(deftest attach-dom-events-ignores-configured-click-when-resolve-returns-nil
  (let [dom (JSDOM. "<div id=\"root\"><button id=\"save\">Save</button></div>")
        document (-> dom (.-window) (.-document))
        root (.querySelector document "#root")
        button (.querySelector document "#save")
        native-event (.createEvent document "Event")
        !calls (atom [])
        !submitted-events (atom [])
        resolve (fn [event]
                  (swap! !calls conj [:resolve event])
                  nil)
        translator (fn [event context]
                     (swap! !calls conj [:translate event context])
                     {:type :task/save-clicked})
        submit! (fn [event]
                  (swap! !calls conj [:submit event])
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (.initEvent native-event "click" true true)
    (dom/attach-dom-events! {:root root
                             :root-node root
                             :events ["click"]
                             :resolve resolve
                             :translators {"click" translator}
                             :submit submit!
                             :submit! submit!})
    (.dispatchEvent button native-event)
    (is (= [[:resolve native-event]]
           @!calls))
    (is (= [] @!submitted-events))))

(deftest attach-dom-events-default-click-resolution-finds-nearest-attribute-and-submits-translated-event
  (let [dom (JSDOM. (str "<div id=\"root\">"
                         "  <section id=\"action\" data-pavlov-on-click=\":task/save\">"
                         "    <button id=\"save\"><span id=\"label\">Save</span></button>"
                         "  </section>"
                         "</div>"))
        document (-> dom (.-window) (.-document))
        root (.querySelector document "#root")
        label (.querySelector document "#label")
        native-event (.createEvent document "Event")
        !submitted-events (atom [])
        translator (fn [_ context]
                     {:type :task/save-clicked
                      :dom/event-name (:dom/event-name context)
                      :matched-el-id (.getAttribute (:matched-el context) "id")
                      :attr-name (:attr-name context)
                      :attr-value (:attr-value context)})
        submit! (fn [event]
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (.initEvent native-event "click" true true)
    (dom/attach-dom-events! {:root root
                             :events ["click"]
                             :translators {"click" translator}
                             :submit! submit!})
    (.dispatchEvent label native-event)
    (is (= [{:type :task/save-clicked
             :dom/event-name "click"
             :matched-el-id "action"
             :attr-name "data-pavlov-on-click"
             :attr-value ":task/save"}]
           @!submitted-events))))

(deftest attach-dom-events-uses-default-change-reset-and-keydown-listeners-and-translators
  (let [dom (JSDOM. (str "<div id=\"root\">"
                         "  <form id=\"task-form\" data-pavlov-on-reset=\":task-form/reset\">"
                         "    <input id=\"task\""
                         "           name=\"task\""
                         "           type=\"text\""
                         "           value=\"Buy milk\""
                         "           data-pavlov-on-change=\":task-form/input-changed\">"
                         "    <button name=\"intent\" value=\"clear\" type=\"reset\">Reset</button>"
                         "  </form>"
                         "  <section id=\"shortcut-scope\" data-pavlov-on-keydown=\":task-form/shortcut\">"
                         "    <input id=\"shortcut-target\" name=\"shortcut\" type=\"text\">"
                         "  </section>"
                         "</div>"))
        window (.-window dom)
        document (.-document window)
        root (.querySelector document "#root")
        task-input (.querySelector document "#task")
        form (.querySelector document "#task-form")
        shortcut-scope (.querySelector document "#shortcut-scope")
        shortcut-target (.querySelector document "#shortcut-target")
        change-event (.createEvent document "Event")
        reset-event (.createEvent document "Event")
        keydown-event (new (.-KeyboardEvent window)
                           "keydown"
                           #js {:bubbles true
                                :key "Enter"
                                :code "Enter"
                                :altKey false
                                :ctrlKey true
                                :metaKey false
                                :shiftKey true
                                :repeat false})
        !submitted-events (atom [])
        submit! (fn [event]
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (.initEvent change-event "change" true true)
    (.initEvent reset-event "reset" true true)
    (dom/attach-dom-events! {:root root
                             :submit! submit!})
    (.dispatchEvent task-input change-event)
    (.dispatchEvent form reset-event)
    (.dispatchEvent shortcut-target keydown-event)
    (is (= [{:type :task-form/input-changed
             :dom/event-name "change"
             :dom/input {:name "task"
                         :value "Buy milk"
                         :target {:id "task"
                                  :tag "input"
                                  :type "text"}}}
            {:type :task-form/reset
             :dom/event-name "reset"
             :dom/form {:values {"task" "Buy milk"}}}
            {:type :task-form/shortcut
             :dom/event-name "keydown"
             :dom/target {:id "shortcut-target"
                          :tag "input"
                          :type "text"}
             :dom/matched {:id "shortcut-scope"
                           :tag "section"}
             :dom/key {:key "Enter"
                       :code "Enter"
                       :alt? false
                       :ctrl? true
                       :meta? false
                       :shift? true
                       :repeat? false}}]
           @!submitted-events))))

(deftest default-input-translator-includes-minimal-target-identity-for-text-input-events
  (let [document (-> (JSDOM. "<input id=\"task\" name=\"task\" value=\"Buy milk\">")
                     (.-window)
                     (.-document))
        input (.querySelector document "#task")
        native-event (.createEvent document "Event")
        context {:dom/event-name "input"
                 :attr-value ":task-form/input-changed"}
        input-translator (get dom/built-in-default-translators "input")]
    (.initEvent native-event "input" true true)
    (.dispatchEvent input native-event)
    (is (= {:type :task-form/input-changed
            :dom/event-name "input"
            :dom/input {:name "task"
                        :value "Buy milk"
                        :target {:id "task"
                                 :tag "input"
                                 :type "text"}}}
           (input-translator native-event context)))))

(deftest default-input-translator-includes-checked-state-for-checkbox-input-events
  (let [document (-> (JSDOM. "<input id=\"done\" name=\"done\" type=\"checkbox\" value=\"yes\" checked>")
                     (.-window)
                     (.-document))
        input (.querySelector document "#done")
        native-event (.createEvent document "Event")
        context {:dom/event-name "input"
                 :attr-value ":task-form/input-changed"}
        input-translator (get dom/built-in-default-translators "input")]
    (.initEvent native-event "input" true true)
    (.dispatchEvent input native-event)
    (is (= {:type :task-form/input-changed
            :dom/event-name "input"
            :dom/input {:name "done"
                        :value "yes"
                        :checked? true
                        :target {:id "done"
                                 :tag "input"
                                 :type "checkbox"}}}
           (input-translator native-event context)))))

(deftest default-submit-translator-serializes-simple-form-values-but-excludes-named-submit-buttons
  (let [document (-> (JSDOM. (str "<form id=\"task-form\">"
                                  "  <input name=\"task\" value=\"Buy milk\">"
                                  "  <input name=\"priority\" value=\"high\">"
                                  "  <button name=\"intent\" value=\"save\" type=\"submit\">Save</button>"
                                  "</form>"))
                     (.-window)
                     (.-document))
        form (.querySelector document "#task-form")
        native-event (.createEvent document "Event")
        context {:dom/event-name "submit"
                 :attr-value ":task-form/submitted"}
        submit-translator (get dom/built-in-default-translators "submit")]
    (.initEvent native-event "submit" true true)
    (.dispatchEvent form native-event)
    (is (= {:type :task-form/submitted
            :dom/event-name "submit"
            :dom/form {:values {"task" "Buy milk"
                                "priority" "high"}}}
           (submit-translator native-event context)))))

(deftest default-submit-translator-includes-submitter-identity-without-changing-form-values
  (let [document (-> (JSDOM. (str "<form id=\"task-form\">"
                                  "  <input name=\"task\" value=\"Buy milk\">"
                                  "  <input name=\"priority\" value=\"high\">"
                                  "  <button id=\"save\" name=\"intent\" value=\"save\" type=\"submit\">Save</button>"
                                  "</form>"))
                     (.-window)
                     (.-document))
        form (.querySelector document "#task-form")
        submitter (.querySelector document "#save")
        native-event (.createEvent document "Event")
        context {:dom/event-name "submit"
                 :attr-value ":task-form/submitted"}
        submit-translator (get dom/built-in-default-translators "submit")]
    (.initEvent native-event "submit" true true)
    (js/Object.defineProperty native-event
                              "submitter"
                              #js {:value submitter
                                   :configurable true})
    (.dispatchEvent form native-event)
    (is (= {:type :task-form/submitted
            :dom/event-name "submit"
            :dom/form {:values {"task" "Buy milk"
                                "priority" "high"}
                       :submitter {:id "save"
                                   :name "intent"
                                   :value "save"}}}
           (submit-translator native-event context)))))

(deftest get-default-translators-includes-click-translator-with-minimal-target-and-matched-identity
  (let [document (-> (JSDOM. (str "<section id=\"action\" data-pavlov-on-click=\":task/save\">"
                                  "  <button id=\"save\" type=\"button\">Save</button>"
                                  "</section>"))
                     (.-window)
                     (.-document))
        button (.querySelector document "#save")
        action (.querySelector document "#action")
        native-event (.createEvent document "Event")
        context {:dom/event-name "click"
                 :attr-value ":task/save"
                 :matched-el action}
        translator (get dom/built-in-default-translators "click")]
    (.initEvent native-event "click" true true)
    (.dispatchEvent button native-event)
    (is (= {:type :task/save
            :dom/event-name "click"
            :dom/target {:id "save"
                         :tag "button"
                         :type "button"}
            :dom/matched {:id "action"
                          :tag "section"}}
           (translator native-event context)))))

(deftest get-default-translators-includes-focus-translators-with-pure-data-payloads
  (let [document (-> (JSDOM. (str "<section id=\"action\""
                                  " data-pavlov-on-focusin=\":task/focused\""
                                  " data-pavlov-on-focusout=\":task/blurred\">"
                                  "  <input id=\"task\" name=\"task\">"
                                  "  <input id=\"next\" name=\"next\">"
                                  "</section>"))
                     (.-window)
                     (.-document))
        action (.querySelector document "#action")
        input (.querySelector document "#task")
        next-input (.querySelector document "#next")
        native-event (.createEvent document "Event")
        translators dom/built-in-default-translators
        focusin-translator (get translators "focusin")
        focusout-translator (get translators "focusout")
        context {:dom/event-name "focusout"
                 :attr-value ":task/blurred"
                 :matched-el action}]
    (.initEvent native-event "focusout" true true)
    (js/Object.defineProperty native-event
                              "relatedTarget"
                              #js {:value next-input
                                   :configurable true})
    (.dispatchEvent input native-event)
    (is (fn? focusin-translator))
    (is (fn? focusout-translator))
    (is (= {:type :task/blurred
            :dom/event-name "focusout"
            :dom/target {:id "task"
                         :tag "input"
                         :type "text"}
            :dom/matched {:id "action"
                          :tag "section"}
            :dom/related-target {:id "next"
                                 :tag "input"
                                 :type "text"}}
           (focusout-translator native-event context)))))

(deftest get-default-translators-includes-change-translator-for-text-input-commits
  (let [document (-> (JSDOM. "<input id=\"task\" name=\"task\" value=\"Buy milk\">")
                     (.-window)
                     (.-document))
        input (.querySelector document "#task")
        native-event (.createEvent document "Event")
        context {:dom/event-name "change"
                 :attr-value ":task-form/input-changed"}
        translator (get dom/built-in-default-translators "change")]
    (.initEvent native-event "change" true true)
    (.dispatchEvent input native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :task-form/input-changed
              :dom/event-name "change"
              :dom/input {:name "task"
                          :value "Buy milk"
                          :target {:id "task"
                                   :tag "input"
                                   :type "text"}}}
             (translator native-event context))))))

(deftest get-default-translators-includes-reset-translator-for-pure-data-form-values
  (let [document (-> (JSDOM. (str "<form id=\"task-form\">"
                                  "  <input name=\"task\" value=\"Buy milk\">"
                                  "  <input name=\"priority\" value=\"high\">"
                                  "  <button name=\"intent\" value=\"clear\" type=\"reset\">Reset</button>"
                                  "</form>"))
                     (.-window)
                     (.-document))
        form (.querySelector document "#task-form")
        native-event (.createEvent document "Event")
        context {:dom/event-name "reset"
                 :attr-value ":task-form/reset"}
        translator (get dom/built-in-default-translators "reset")]
    (.initEvent native-event "reset" true true)
    (.dispatchEvent form native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :task-form/reset
              :dom/event-name "reset"
              :dom/form {:values {"task" "Buy milk"
                                  "priority" "high"}}}
             (translator native-event context))))))

(deftest get-default-translators-includes-keydown-translator-with-pure-data-key-metadata
  (let [dom (JSDOM. (str "<section id=\"shortcut-scope\" data-pavlov-on-keydown=\":task-form/shortcut\">"
                         "  <input id=\"task\" name=\"task\" type=\"text\">"
                         "</section>"))
        window (.-window dom)
        document (.-document window)
        input (.querySelector document "#task")
        scope (.querySelector document "#shortcut-scope")
        native-event (new (.-KeyboardEvent window)
                          "keydown"
                          #js {:bubbles true
                               :key "Enter"
                               :code "Enter"
                               :altKey true
                               :ctrlKey false
                               :metaKey false
                               :shiftKey true
                               :repeat true})
        context {:dom/event-name "keydown"
                 :attr-value ":task-form/shortcut"
                 :matched-el scope}
        translator (get dom/built-in-default-translators "keydown")]
    (.dispatchEvent input native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :task-form/shortcut
              :dom/event-name "keydown"
              :dom/target {:id "task"
                           :tag "input"
                           :type "text"}
              :dom/matched {:id "shortcut-scope"
                            :tag "section"}
              :dom/key {:key "Enter"
                        :code "Enter"
                        :alt? true
                        :ctrl? false
                        :meta? false
                        :shift? true
                        :repeat? true}}
             (translator native-event context))))))
