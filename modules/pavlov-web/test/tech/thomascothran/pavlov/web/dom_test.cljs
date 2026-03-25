(ns tech.thomascothran.pavlov.web.dom-test
  (:require [cljs.test :refer-macros [deftest is]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.web.dom :as dom]
            ["jsdom" :refer [JSDOM]]))

(defn- make-fake-timeouts
  []
  (let [!scheduled (atom [])
        !cleared (atom [])
        !next-id (atom 0)
        set-timeout! (fn [f ms]
                       (let [token (keyword (str "timer-" (swap! !next-id inc)))]
                         (swap! !scheduled conj {:token token
                                                 :f f
                                                 :ms ms})
                         token))
        clear-timeout! (fn [token]
                         (swap! !cleared conj token))]
    {:!scheduled !scheduled
     :!cleared !cleared
     :set-timeout! set-timeout!
     :clear-timeout! clear-timeout!}))

(defn- define-readonly!
  [obj property-name value]
  (js/Object.defineProperty obj
                            property-name
                            #js {:value value
                                 :configurable true}))

(defn- make-drag-data-transfer
  []
  #js {:dropEffect "move"
       :effectAllowed "move"
       :types #js ["text/plain" "application/x.pavlov-column"]
       :items #js [#js {:kind "string"
                        :type "text/plain"}
                  #js {:kind "string"
                       :type "application/x.pavlov-column"}]})

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

(deftest make-dom-op-bthread-reorders-children-by-id
  (let [document (-> (JSDOM. (str "<table><tbody id=\"grid-body\">"
                                  "<tr id=\"row-2\"></tr>"
                                  "<tr id=\"row-1\"></tr>"
                                  "</tbody></table>"))
                     (.-window)
                     (.-document))
        tbody (.querySelector document "#grid-body")
        bthread (dom/make-dom-op-bthread #(.querySelectorAll document %))]
    (b/notify! bthread nil)
    (b/notify! bthread {:type :pavlov.web.dom/op
                        :selector "#grid-body"
                        :kind :reorder-children
                        :child-ids ["row-1" "row-2"]})
    (is (= ["row-1" "row-2"]
           (->> (array-seq (.querySelectorAll tbody "tr"))
                (map #(.getAttribute % "id")))))))

(deftest make-dom-op-bthread-runs-multiple-dom-ops-from-batched-event
  (let [document (-> (JSDOM. "<input id=\"search\" value=\"before\"><div id=\"status\">idle</div>")
                     (.-window)
                     (.-document))
        input (.querySelector document "#search")
        status (.querySelector document "#status")
        bthread (dom/make-dom-op-bthread #(.querySelectorAll document %))]
    (b/notify! bthread nil)
    (b/notify! bthread {:type :pavlov.web.dom/ops
                        :ops [{:selector "#search"
                               :kind :set
                               :member "value"
                               :value "after"}
                              {:selector "#status"
                               :kind :set
                               :member "textContent"
                               :value "loading"}
                              {:selector "#status"
                               :kind :set
                               :member "textContent"
                               :value "ready"}]})
     (is (= "after" (.-value input)))
     (is (= "ready" (.-textContent status)))))

(deftest make-dom-op-bthread-instantiates-template-fragment-from-batched-event
  (let [document (-> (JSDOM. (str "<ul id=\"task-list\"></ul>"
                                  "<template id=\"task-row-template\">"
                                  "<li class=\"task-row\">"
                                  "<span data-role=\"label\"></span>"
                                  "<span data-role=\"status\"></span>"
                                  "</li>"
                                  "</template>"))
                     (.-window)
                     (.-document))
        template (.querySelector document "#task-row-template")
        task-list (.querySelector document "#task-list")
        bthread (dom/make-dom-op-bthread #(.querySelectorAll document %))]
    (b/notify! bthread nil)
    (b/notify! bthread {:type :pavlov.web.dom/ops
                        :ops [{:kind :instantiate-fragment
                               :source {:kind :template
                                        :selector "#task-row-template"}
                               :mutations [{:local-selector "[data-role='label']"
                                            :kind :set
                                            :member "textContent"
                                            :value "Buy milk"}
                                           {:local-selector "[data-role='status']"
                                            :kind :call
                                            :member "setAttribute"
                                            :args ["data-state" "pending"]}]
                               :attach {:selector "#task-list"
                                        :position :append}}]})
    (is (= 1 (.-length (.querySelectorAll task-list ".task-row"))))
    (is (= "Buy milk"
           (.-textContent (.querySelector task-list "[data-role='label']"))))
    (is (= "pending"
           (.getAttribute (.querySelector task-list "[data-role='status']")
                          "data-state")))
    (is (= ""
           (.-textContent (.querySelector (.-content template)
                                          "[data-role='label']"))))))

(deftest make-dom-event-redirect-bthread-defaults-to-built-in-raw-dom-event-types-and-requests-retargeted-semantic-event-only-from-the-matching-keyword-valued-copied-pavlov-on-key
  (let [dom-event-types (into #{} (map (comp keyword #(str "dom/" %))) (keys dom/built-in-default-translators))
        bthread (dom/make-dom-event-redirect-bthread)
        incoming-event {:type :dom/click
                        :pavlov-on-click :task/save
                        :pavlov-on-input ":task/rename"
                        :dom/event-name "click"
                        :dom/target {:id "save"
                                     :tag "button"
                                     :type "button"}
                        :dom/matched {:id "action"
                                      :tag "section"}
                        :pavlov-entity-id "task-42"}
        init-bid (b/notify! bthread nil)
        bid (b/notify! bthread incoming-event)]
    (is (= {:wait-on dom-event-types}
           init-bid))
    (is (= {:wait-on dom-event-types
            :request #{{:type :task/save
                        :pavlov-on-click :task/save
                        :pavlov-on-input ":task/rename"
                        :dom/event-name "click"
                        :dom/target {:id "save"
                                     :tag "button"
                                     :type "button"}
                        :dom/matched {:id "action"
                                      :tag "section"}
                        :pavlov-entity-id "task-42"}}}
           bid))))

(deftest make-dom-event-redirect-bthread-emits-nothing-without-a-matching-copied-pavlov-on-key
  (let [dom-event-types (into #{} (map (comp keyword #(str "dom/" %))) (keys dom/built-in-default-translators))
        bthread (dom/make-dom-event-redirect-bthread)
        event-without-matching-key {:type :dom/input
                                    :dom/event-name "input"
                                    :dom/input {:name "task"
                                                :value "Buy milk"
                                                :target {:id "task"
                                                         :tag "input"
                                                         :type "text"}}
                                    :pavlov-input-scope "primary"}
        event-with-only-different-key {:type :dom/input
                                       :dom/event-name "input"
                :pavlov-on-click :task/save
                                       :dom/input {:name "task"
                                                   :value "Buy milk"
                                                   :target {:id "task"
                                                            :tag "input"
                                                            :type "text"}}
                                       :pavlov-input-scope "primary"}
         init-bid (b/notify! bthread nil)]
    (is (= {:wait-on dom-event-types}
           init-bid))
    (is (= {:wait-on dom-event-types}
           (b/notify! bthread event-without-matching-key)))
    (is (= {:wait-on dom-event-types}
           (b/notify! bthread event-with-only-different-key)))))

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
                          :attr-name "pavlov-on-click"
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

(deftest attach-dom-events-default-click-resolution-only-opts-in-through-pavlov-on-click-and-copies-it-to-the-raw-event
  (let [dom (JSDOM. (str "<div id=\"root\">"
                          "  <section id=\"action\""
                         "           pavlov-on-click=\":task/save\""
                         "           pavlov-entity-id=\"task-42\">"
                         "    <div id=\"ignored\" pavlov-on-input=\":task/rename\">"
                         "      <button id=\"save\"><span id=\"label\">Save</span></button>"
                         "    </div>"
                         "  </section>"
                         "</div>"))
        document (-> dom (.-window) (.-document))
        root (.querySelector document "#root")
        label (.querySelector document "#label")
        native-event (.createEvent document "Event")
        !submitted-events (atom [])
        translator (fn [_ context]
                     {:type :dom/click
                       :dom/event-name (:dom/event-name context)
                       :matched-el-id (.getAttribute (:matched-el context) "id")
                       :attr-name (:attr-name context)
                       :attr-value (:attr-value context)
                       :pavlov-on-click (:pavlov-on-click context)
                       :pavlov-entity-id (:pavlov-entity-id context)})
         submit! (fn [event]
                   (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (.initEvent native-event "click" true true)
    (dom/attach-dom-events! {:root root
                             :events ["click"]
                             :translators {"click" translator}
                             :submit! submit!})
    (.dispatchEvent label native-event)
     (is (= [{:type :dom/click
              :dom/event-name "click"
              :matched-el-id "action"
              :attr-name "pavlov-on-click"
              :attr-value ":task/save"
              :pavlov-on-click ":task/save"
               :pavlov-entity-id "task-42"}]
            @!submitted-events))))

(deftest attach-dom-events-default-click-resolution-captures-elements-in-dom-order-with-pavlov-attrs-and-input-values
  (let [dom (JSDOM. (str "<div id=\"root\">"
                         "  <section id=\"grid\""
                         "           pavlov-on-click=\":grid/sort-clicked\""
                         "           pavlov-grid-id=\"telemetry\""
                         "           pavlov-capture-selector=\"#grid-filter, #grid-body > [data-grid-row], #grid-body > [data-grid-row] > [pavlov-latency-sort-value]\">"
                         "    <input id=\"grid-filter\""
                         "           type=\"text\""
                         "           value=\"syn\""
                         "           pavlov-filter-key=\"node-id\">"
                         "    <table><tbody id=\"grid-body\">"
                         "      <tr id=\"row-1\" data-grid-row>"
                         "        <td>SYNTH-NODE-001</td>"
                         "        <td pavlov-latency-sort-value=\"0.82\">0.82 ms</td>"
                         "      </tr>"
                         "      <tr id=\"row-2\" data-grid-row>"
                         "        <td>SYNTH-NODE-042</td>"
                         "        <td pavlov-latency-sort-value=\"12.5\">12.5 ms</td>"
                         "      </tr>"
                         "    </tbody></table>"
                         "    <button id=\"sort\" type=\"button\">Sort</button>"
                         "  </section>"
                         "</div>"))
        document (-> dom (.-window) (.-document))
        root (.querySelector document "#root")
        button (.querySelector document "#sort")
        native-event (.createEvent document "Event")
        !submitted-events (atom [])
        submit! (fn [event]
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (.initEvent native-event "click" true true)
    (dom/attach-dom-events! {:root root
                             :events ["click"]
                             :submit! submit!})
    (.dispatchEvent button native-event)
    (is (= [{:type :dom/click
             :dom/event-name "click"
             :pavlov-on-click :grid/sort-clicked
             :pavlov-grid-id "telemetry"
             :pavlov-capture-selector "#grid-filter, #grid-body > [data-grid-row], #grid-body > [data-grid-row] > [pavlov-latency-sort-value]"
             :dom/target {:id "sort"
                          :tag "button"
                          :type "button"}
             :dom/matched {:id "grid"
                           :tag "section"}
             :dom/children [{:id "grid-filter"
                             :tag "input"
                             :type "text"
                             :dom/value "syn"
                             :pavlov-filter-key "node-id"}
                            {:id "row-1"
                             :tag "tr"}
                            {:id ""
                             :tag "td"
                             :pavlov-latency-sort-value "0.82"}
                            {:id "row-2"
                             :tag "tr"}
                            {:id ""
                             :tag "td"
                             :pavlov-latency-sort-value "12.5"}]}]
           @!submitted-events))))

(deftest attach-dom-events-default-click-resolution-captures-elements-from-root-scoped-selector
  (let [dom (JSDOM. (str "<div id=\"root\">"
                         "  <section id=\"grid\">"
                         "    <button id=\"sort\""
                          "            type=\"button\""
                          "            pavlov-on-click=\":grid/sort-clicked\""
                          "            pavlov-grid-id=\"telemetry\""
                         "            pavlov-capture-selector=\"#grid-body > [data-grid-row], #grid-body > [data-grid-row] > [pavlov-node-id-sort-value]\">Sort</button>"
                          "    <table><tbody id=\"grid-body\">"
                         "      <tr id=\"row-2\" data-grid-row>"
                         "        <td pavlov-node-id-sort-value=\"SYNTH-NODE-042\">SYNTH-NODE-042</td>"
                         "      </tr>"
                         "      <tr id=\"row-1\" data-grid-row>"
                         "        <td pavlov-node-id-sort-value=\"SYNTH-NODE-001\">SYNTH-NODE-001</td>"
                         "      </tr>"
                         "    </tbody></table>"
                         "  </section>"
                         "</div>"))
        document (-> dom (.-window) (.-document))
        root (.querySelector document "#root")
        button (.querySelector document "#sort")
        native-event (.createEvent document "Event")
        !submitted-events (atom [])
        submit! (fn [event]
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (.initEvent native-event "click" true true)
    (dom/attach-dom-events! {:root root
                             :events ["click"]
                             :submit! submit!})
    (.dispatchEvent button native-event)
    (is (= [{:id "row-2"
             :tag "tr"}
            {:id ""
             :tag "td"
             :pavlov-node-id-sort-value "SYNTH-NODE-042"}
            {:id "row-1"
             :tag "tr"}
            {:id ""
             :tag "td"
             :pavlov-node-id-sort-value "SYNTH-NODE-001"}]
           (:dom/children (first @!submitted-events))))))

(deftest attach-dom-events-default-resolution-supports-custom-attr-prefix
  (let [dom (JSDOM. (str "<div id=\"root\">"
                         "  <input id=\"task\""
                         "         name=\"task\""
                         "         value=\"Buy milk\""
                         "         bp-on-input=\":task-form/input-changed\""
                         "         bp-input-scope=\"primary\">"
                         "</div>"))
        document (-> dom (.-window) (.-document))
        root (.querySelector document "#root")
        input (.querySelector document "#task")
        native-event (.createEvent document "Event")
        !submitted-events (atom [])
        submit! (fn [event]
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (.initEvent native-event "input" true true)
    (dom/attach-dom-events! {:root root
                             :events ["input"]
                             :attr-prefix "bp"
                             :submit! submit!})
    (.dispatchEvent input native-event)
    (is (= [{:type :dom/input
             :dom/event-name "input"
             :bp-on-input :task-form/input-changed
             :bp-input-scope "primary"
             :dom/input {:name "task"
                         :value "Buy milk"
                         :target {:id "task"
                                  :tag "input"
                                  :type "text"}}}]
           @!submitted-events))))

(deftest attach-dom-events-uses-default-change-reset-and-keydown-listeners-and-translators
  (let [dom (JSDOM. (str "<div id=\"root\">"
         "  <form id=\"task-form\""
         "        pavlov-on-reset=\":task-form/reset\""
         "        pavlov-form-id=\"task-form\">"
                         "    <input id=\"task\""
                         "           name=\"task\""
                         "           type=\"text\""
                         "           value=\"Buy milk\""
         "           pavlov-on-change=\":task-form/input-changed\""
         "           pavlov-input-scope=\"primary\">"
                         "    <button name=\"intent\" value=\"clear\" type=\"reset\">Reset</button>"
                         "  </form>"
         "  <section id=\"shortcut-scope\""
         "           pavlov-on-keydown=\":task-form/shortcut\""
         "           pavlov-shortcut-scope=\"task-form\">"
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
     (is (= [{:type :dom/change
               :dom/event-name "change"
               :pavlov-on-change :task-form/input-changed
               :pavlov-input-scope "primary"
               :dom/input {:name "task"
                           :value "Buy milk"
                           :target {:id "task"
                                    :tag "input"
                                    :type "text"}}}
              {:type :dom/reset
               :dom/event-name "reset"
               :pavlov-on-reset :task-form/reset
               :pavlov-form-id "task-form"
               :dom/form {:values {"task" "Buy milk"}}}
              {:type :dom/keydown
               :dom/event-name "keydown"
               :pavlov-on-keydown :task-form/shortcut
               :pavlov-shortcut-scope "task-form"
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

(deftest attach-dom-events-default-submit-prevents-default-for-opted-in-form-submits
  (let [dom (JSDOM. (str "<div id=\"root\">"
                         "  <form id=\"task-form\""
                         "        pavlov-on-submit=\":task-form/submitted\""
                         "        pavlov-form-id=\"task-form\">"
                         "    <input name=\"task\" value=\"Buy milk\">"
                         "    <button id=\"save\" type=\"submit\">Save</button>"
                         "  </form>"
                         "</div>"))
        window (.-window dom)
        document (.-document window)
        root (.querySelector document "#root")
        form (.querySelector document "#task-form")
        submit-event (new (.-Event window)
                          "submit"
                          #js {:bubbles true
                               :cancelable true})
        !submitted-events (atom [])
        submit! (fn [event]
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (dom/attach-dom-events! {:root root
                             :submit! submit!})
    (is (false? (.dispatchEvent form submit-event)))
    (is (true? (.-defaultPrevented submit-event)))
    (is (= [{:type :dom/submit
             :dom/event-name "submit"
             :pavlov-on-submit :task-form/submitted
             :pavlov-form-id "task-form"
             :dom/form {:values {"task" "Buy milk"}}}]
           @!submitted-events))))

(deftest attach-dom-events-default-dragover-prevents-default-for-opted-in-drop-targets
  (let [dom (JSDOM. (str "<div id=\"root\">"
                         "  <section id=\"drop-zone\""
                         "           pavlov-on-dragover=\":grid/column-drag-over\""
                         "           pavlov-column-id=\"beans\">"
                         "    <button id=\"beans\" type=\"button\">Beans</button>"
                         "  </section>"
                         "</div>"))
        window (.-window dom)
        document (.-document window)
        root (.querySelector document "#root")
        button (.querySelector document "#beans")
        dragover-event (new (.-MouseEvent window)
                            "dragover"
                            #js {:bubbles true
                                 :cancelable true
                                 :clientX 225
                                 :clientY 31
                                 :altKey true
                                 :ctrlKey false
                                 :metaKey false
                                 :shiftKey true})
        !submitted-events (atom [])
        submit! (fn [event]
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (define-readonly! dragover-event "dataTransfer" (make-drag-data-transfer))
    (dom/attach-dom-events! {:root root
                             :submit! submit!})
    (is (false? (.dispatchEvent button dragover-event)))
    (is (true? (.-defaultPrevented dragover-event)))
    (is (= [{:type :dom/dragover
             :dom/event-name "dragover"
             :pavlov-on-dragover :grid/column-drag-over
             :pavlov-column-id "beans"
             :dom/target {:id "beans"
                          :tag "button"
                          :type "button"}
             :dom/matched {:id "drop-zone"
                           :tag "section"}
             :dom/drag {:client-x 225
                        :client-y 31
                        :alt? true
                        :ctrl? false
                        :meta? false
                        :shift? true
                        :drop-effect "move"
                        :effect-allowed "move"
                        :types ["text/plain" "application/x.pavlov-column"]
                        :items [{:kind "string"
                                 :type "text/plain"}
                                {:kind "string"
                                 :type "application/x.pavlov-column"}]}}]
           @!submitted-events))))

(deftest attach-dom-events-default-drop-prevents-default-for-opted-in-drop-targets
  (let [dom (JSDOM. (str "<div id=\"root\">"
                         "  <section id=\"drop-zone\">"
                         "    <button id=\"beans\""
                         "            type=\"button\""
                         "            pavlov-on-drop=\":grid/column-dropped\""
                         "            pavlov-column-id=\"beans\">Beans</button>"
                         "  </section>"
                         "</div>"))
        window (.-window dom)
        document (.-document window)
        root (.querySelector document "#root")
        button (.querySelector document "#beans")
        drop-event (new (.-MouseEvent window)
                        "drop"
                        #js {:bubbles true
                             :cancelable true
                             :clientX 248
                             :clientY 28
                             :altKey false
                             :ctrlKey false
                             :metaKey false
                             :shiftKey true})
        !submitted-events (atom [])
        submit! (fn [event]
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (define-readonly! drop-event "dataTransfer" (make-drag-data-transfer))
    (dom/attach-dom-events! {:root root
                             :submit! submit!})
    (is (false? (.dispatchEvent button drop-event)))
    (is (true? (.-defaultPrevented drop-event)))
    (is (= [{:type :dom/drop
             :dom/event-name "drop"
             :pavlov-on-drop :grid/column-dropped
             :pavlov-column-id "beans"
             :dom/target {:id "beans"
                          :tag "button"
                          :type "button"}
             :dom/matched {:id "beans"
                           :tag "button"}
             :dom/drag {:client-x 248
                        :client-y 28
                        :alt? false
                        :ctrl? false
                        :meta? false
                        :shift? true
                        :drop-effect "move"
                        :effect-allowed "move"
                        :types ["text/plain" "application/x.pavlov-column"]
                        :items [{:kind "string"
                                 :type "text/plain"}
                                {:kind "string"
                                 :type "application/x.pavlov-column"}]}}]
           @!submitted-events))))

(deftest attach-dom-events-uses-the-event-scheduler-for-debounced-input-events
  (let [{:keys [!scheduled !cleared set-timeout! clear-timeout!]} (make-fake-timeouts)
        dom (JSDOM. (str "<div id=\"root\">"
                         "  <input id=\"task\""
                         "         name=\"task\""
                         "         value=\"Buy milk\""
                         "         pavlov-on-input=\":task-form/input-changed\""
                         "         pavlov-input-debounce-ms=\"10\">"
                         "</div>"))
        window (.-window dom)
        document (.-document window)
        root (.querySelector document "#root")
        input (.querySelector document "#task")
        input-event (new (.-Event window) "input" #js {:bubbles true})
        !translated (atom [])
        !submitted-events (atom [])
        translator (fn [event context]
                     (swap! !translated conj [event context])
                     {:type :task-form/debounced-input
                      :value (.-value (.-target event))
                      :semantic-target (:pavlov-on-input context)
                      :debounce-ms (:pavlov-input-debounce-ms context)})
        submit! (fn [event]
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (dom/attach-dom-events! {:root root
                             :events ["input"]
                             :translators {"input" translator}
                             :set-timeout! set-timeout!
                             :clear-timeout! clear-timeout!
                             :submit! submit!})
    (.dispatchEvent input input-event)
    (is (= [] @!translated))
    (is (= [] @!submitted-events))
    (is (= [{:token :timer-1
             :f (:f (first @!scheduled))
             :ms 10}]
           @!scheduled))
    (is (fn? (:f (first @!scheduled))))
    (is (= [] @!cleared))
    ((:f (first @!scheduled)))
    (is (= 1 (count @!translated)))
    (is (= [{:type :task-form/debounced-input
             :value "Buy milk"
             :semantic-target ":task-form/input-changed"
             :debounce-ms "10"}]
           @!submitted-events))))

(deftest attach-dom-events-debounced-input-keeps-only-the-latest-event
  (let [{:keys [!scheduled !cleared set-timeout! clear-timeout!]} (make-fake-timeouts)
        dom (JSDOM. (str "<div id=\"root\">"
                         "  <input id=\"task\""
                         "         name=\"task\""
                         "         pavlov-on-input=\":task-form/input-changed\""
                         "         pavlov-input-debounce-ms=\"10\">"
                         "</div>"))
        window (.-window dom)
        document (.-document window)
        root (.querySelector document "#root")
        input (.querySelector document "#task")
        first-event (new (.-CustomEvent window) "input" #js {:bubbles true
                                                               :detail "Buy"})
        second-event (new (.-CustomEvent window) "input" #js {:bubbles true
                                                                :detail "Buy milk"})
        !translated (atom [])
        !submitted-events (atom [])
        translator (fn [event context]
                     (swap! !translated conj [event context])
                     {:type :task-form/debounced-input
                      :value (.-detail event)})
        submit! (fn [event]
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (dom/attach-dom-events! {:root root
                             :events ["input"]
                             :translators {"input" translator}
                             :set-timeout! set-timeout!
                             :clear-timeout! clear-timeout!
                             :submit! submit!})
    (.dispatchEvent input first-event)
    (.dispatchEvent input second-event)
    (is (= [:timer-1]
           @!cleared))
    (is (= 2
           (count @!scheduled)))
    (is (= [] @!translated))
    (is (= [] @!submitted-events))
    ((:f (second @!scheduled)))
    (is (= [[second-event {:dom/event-name "input"
                           :matched-el input
                           :attr-name "pavlov-on-input"
                           :attr-value ":task-form/input-changed"
                           :pavlov-on-input ":task-form/input-changed"
                           :pavlov-input-debounce-ms "10"}]]
           @!translated))
    (is (= [{:type :task-form/debounced-input
             :value "Buy milk"}]
           @!submitted-events))))

(deftest attach-dom-events-throttled-input-delivers-leading-edge-and-suppresses-until-reopened
  (let [{:keys [!scheduled !cleared set-timeout! clear-timeout!]} (make-fake-timeouts)
        dom (JSDOM. (str "<div id=\"root\">"
                         "  <input id=\"task\""
                         "         name=\"task\""
                         "         pavlov-on-input=\":task-form/input-changed\""
                         "         pavlov-input-throttle-ms=\"10\">"
                         "</div>"))
        window (.-window dom)
        document (.-document window)
        root (.querySelector document "#root")
        input (.querySelector document "#task")
        first-event (new (.-CustomEvent window) "input" #js {:bubbles true
                                                               :detail "Buy"})
        second-event (new (.-CustomEvent window) "input" #js {:bubbles true
                                                                :detail "Buy milk"})
        third-event (new (.-CustomEvent window) "input" #js {:bubbles true
                                                               :detail "Buy milk now"})
        !translated (atom [])
        !submitted-events (atom [])
        translator (fn [event context]
                     (swap! !translated conj [event context])
                     {:type :task-form/throttled-input
                      :value (.-detail event)})
        submit! (fn [event]
                  (swap! !submitted-events conj event))]
    (set! js/global.document document)
    (dom/attach-dom-events! {:root root
                             :events ["input"]
                             :translators {"input" translator}
                             :set-timeout! set-timeout!
                             :clear-timeout! clear-timeout!
                             :submit! submit!})
    (.dispatchEvent input first-event)
    (is (= 1 (count @!translated)))
    (is (= [{:type :task-form/throttled-input
             :value "Buy"}]
           @!submitted-events))
    (is (= [{:token :timer-1
             :f (:f (first @!scheduled))
             :ms 10}]
           @!scheduled))
    (.dispatchEvent input second-event)
    (is (= 1 (count @!translated)))
    (is (= [{:type :task-form/throttled-input
             :value "Buy"}]
           @!submitted-events))
    (is (= [] @!cleared))
    ((:f (first @!scheduled)))
    (.dispatchEvent input third-event)
    (is (= 2 (count @!translated)))
    (is (= [{:type :task-form/throttled-input
             :value "Buy"}
            {:type :task-form/throttled-input
             :value "Buy milk now"}]
           @!submitted-events))))

(deftest default-input-translator-includes-minimal-target-identity-for-text-input-events
  (let [document (-> (JSDOM. "<input id=\"task\" name=\"task\" value=\"Buy milk\">")
                     (.-window)
                     (.-document))
        input (.querySelector document "#task")
        native-event (.createEvent document "Event")
         context {:dom/event-name "input"
                  :pavlov-on-input ":task-form/input-changed"
                  :pavlov-input-scope "primary"}
        input-translator (get dom/built-in-default-translators "input")]
    (.initEvent native-event "input" true true)
    (.dispatchEvent input native-event)
    (is (= {:type :dom/input
            :dom/event-name "input"
             :pavlov-on-input :task-form/input-changed
            :pavlov-input-scope "primary"
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
             :pavlov-on-input :task-form/input-changed
                 :pavlov-input-scope "primary"}
        input-translator (get dom/built-in-default-translators "input")]
    (.initEvent native-event "input" true true)
    (.dispatchEvent input native-event)
    (is (= {:type :dom/input
            :dom/event-name "input"
             :pavlov-on-input :task-form/input-changed
            :pavlov-input-scope "primary"
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
             :pavlov-on-submit :task-form/submitted
                  :pavlov-form-id "task-form"}
        submit-translator (get dom/built-in-default-translators "submit")]
    (.initEvent native-event "submit" true true)
    (.dispatchEvent form native-event)
    (is (= {:type :dom/submit
            :dom/event-name "submit"
             :pavlov-on-submit :task-form/submitted
            :pavlov-form-id "task-form"
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
             :pavlov-on-submit :task-form/submitted
                 :pavlov-form-id "task-form"}
        submit-translator (get dom/built-in-default-translators "submit")]
    (.initEvent native-event "submit" true true)
    (js/Object.defineProperty native-event
                              "submitter"
                              #js {:value submitter
                                   :configurable true})
    (.dispatchEvent form native-event)
    (is (= {:type :dom/submit
            :dom/event-name "submit"
             :pavlov-on-submit :task-form/submitted
            :pavlov-form-id "task-form"
            :dom/form {:values {"task" "Buy milk"
                                "priority" "high"}
                       :submitter {:id "save"
                                   :name "intent"
                                   :value "save"}}}
           (submit-translator native-event context)))))

(deftest get-default-translators-includes-click-translator-with-minimal-target-and-matched-identity
  (let [document (-> (JSDOM. (str "<section id=\"action\" pavlov-on-click=\":task/save\">"
                                   "  <button id=\"save\" type=\"button\">Save</button>"
                                   "</section>"))
                     (.-window)
                     (.-document))
        button (.querySelector document "#save")
        action (.querySelector document "#action")
        native-event (.createEvent document "Event")
         context {:dom/event-name "click"
                  :pavlov-on-click ":task/save"
                  :pavlov-entity-id "task-42"
                  :matched-el action}
        translator (get dom/built-in-default-translators "click")]
    (.initEvent native-event "click" true true)
    (.dispatchEvent button native-event)
    (is (= {:type :dom/click
            :dom/event-name "click"
             :pavlov-on-click :task/save
            :pavlov-entity-id "task-42"
            :dom/target {:id "save"
                         :tag "button"
                         :type "button"}
            :dom/matched {:id "action"
                          :tag "section"}}
           (translator native-event context)))))

(deftest get-default-translators-includes-focus-translators-with-pure-data-payloads
  (let [document (-> (JSDOM. (str "<section id=\"action\""
                                  " pavlov-on-focusin=\":task/focused\""
                                  " pavlov-on-focusout=\":task/blurred\">"
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
                  :pavlov-on-focusout ":task/blurred"
                  :pavlov-focus-scope "task-panel"
                  :matched-el action}]
    (.initEvent native-event "focusout" true true)
    (js/Object.defineProperty native-event
                              "relatedTarget"
                              #js {:value next-input
                                   :configurable true})
    (.dispatchEvent input native-event)
    (is (fn? focusin-translator))
    (is (fn? focusout-translator))
    (is (= {:type :dom/focusout
            :dom/event-name "focusout"
             :pavlov-on-focusout :task/blurred
            :pavlov-focus-scope "task-panel"
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
                  :pavlov-on-change ":task-form/input-changed"
                  :pavlov-input-scope "primary"}
        translator (get dom/built-in-default-translators "change")]
    (.initEvent native-event "change" true true)
    (.dispatchEvent input native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :dom/change
              :dom/event-name "change"
               :pavlov-on-change :task-form/input-changed
              :pavlov-input-scope "primary"
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
                  :pavlov-on-reset ":task-form/reset"
                  :pavlov-form-id "task-form"}
        translator (get dom/built-in-default-translators "reset")]
    (.initEvent native-event "reset" true true)
    (.dispatchEvent form native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :dom/reset
              :dom/event-name "reset"
               :pavlov-on-reset :task-form/reset
              :pavlov-form-id "task-form"
              :dom/form {:values {"task" "Buy milk"
                                  "priority" "high"}}}
             (translator native-event context))))))

(deftest get-default-translators-includes-keydown-translator-with-pure-data-key-metadata
  (let [dom (JSDOM. (str "<section id=\"shortcut-scope\" pavlov-on-keydown=\":task-form/shortcut\">"
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
                  :pavlov-on-keydown ":task-form/shortcut"
                  :pavlov-shortcut-scope "task-form"
                  :matched-el scope}
        translator (get dom/built-in-default-translators "keydown")]
    (.dispatchEvent input native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :dom/keydown
              :dom/event-name "keydown"
               :pavlov-on-keydown :task-form/shortcut
              :pavlov-shortcut-scope "task-form"
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

(deftest get-default-translators-includes-drag-translator-with-pure-data-drag-metadata
  (let [dom (JSDOM. (str "<section id=\"drag-scope\" pavlov-on-drag=\":grid/dragging\" pavlov-grid-id=\"telemetry\">"
                         "  <button id=\"apples\" type=\"button\" draggable=\"true\">Apples</button>"
                         "</section>"))
        window (.-window dom)
        document (.-document window)
        button (.querySelector document "#apples")
        scope (.querySelector document "#drag-scope")
        native-event (new (.-MouseEvent window)
                          "drag"
                          #js {:bubbles true
                               :clientX 412
                               :clientY 18
                               :altKey false
                               :ctrlKey true
                               :metaKey false
                               :shiftKey true})
        context {:dom/event-name "drag"
                 :pavlov-on-drag ":grid/dragging"
                 :pavlov-grid-id "telemetry"
                 :matched-el scope}
        translator (get dom/built-in-default-translators "drag")]
    (define-readonly! native-event "dataTransfer" (make-drag-data-transfer))
    (.dispatchEvent button native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :dom/drag
              :dom/event-name "drag"
              :pavlov-on-drag :grid/dragging
              :pavlov-grid-id "telemetry"
              :dom/target {:id "apples"
                           :tag "button"
                           :type "button"}
              :dom/matched {:id "drag-scope"
                            :tag "section"}
              :dom/drag {:client-x 412
                         :client-y 18
                         :alt? false
                         :ctrl? true
                         :meta? false
                         :shift? true
                         :drop-effect "move"
                         :effect-allowed "move"
                         :types ["text/plain" "application/x.pavlov-column"]
                         :items [{:kind "string"
                                  :type "text/plain"}
                                 {:kind "string"
                                  :type "application/x.pavlov-column"}]}}
             (translator native-event context))))))

(deftest get-default-translators-includes-dragstart-translator-for-drag-source-identity-and-metadata
  (let [dom (JSDOM. (str "<section id=\"drag-scope\">"
                         "  <button id=\"apples\""
                         "          type=\"button\""
                         "          draggable=\"true\""
                         "          pavlov-on-dragstart=\":grid/column-drag-started\""
                         "          pavlov-column-id=\"apples\">Apples</button>"
                         "</section>"))
        window (.-window dom)
        document (.-document window)
        button (.querySelector document "#apples")
        native-event (new (.-MouseEvent window)
                          "dragstart"
                          #js {:bubbles true
                               :clientX 120
                               :clientY 24
                               :altKey false
                               :ctrlKey false
                               :metaKey false
                               :shiftKey false})
        context {:dom/event-name "dragstart"
                 :pavlov-on-dragstart ":grid/column-drag-started"
                 :pavlov-column-id "apples"
                 :matched-el button}
        translator (get dom/built-in-default-translators "dragstart")]
    (define-readonly! native-event "dataTransfer" (make-drag-data-transfer))
    (.dispatchEvent button native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :dom/dragstart
              :dom/event-name "dragstart"
              :pavlov-on-dragstart :grid/column-drag-started
              :pavlov-column-id "apples"
              :dom/target {:id "apples"
                           :tag "button"
                           :type "button"}
              :dom/matched {:id "apples"
                            :tag "button"}
              :dom/drag {:client-x 120
                         :client-y 24
                         :alt? false
                         :ctrl? false
                         :meta? false
                         :shift? false
                         :drop-effect "move"
                         :effect-allowed "move"
                         :types ["text/plain" "application/x.pavlov-column"]
                         :items [{:kind "string"
                                  :type "text/plain"}
                                 {:kind "string"
                                  :type "application/x.pavlov-column"}]}}
             (translator native-event context))))))

(deftest get-default-translators-includes-dragover-translator-for-drop-target-metadata
  (let [dom (JSDOM. (str "<section id=\"drag-scope\""
                         "          pavlov-on-dragover=\":grid/column-drag-over\""
                         "          pavlov-column-id=\"beans\">"
                         "  <button id=\"beans\" type=\"button\">Beans</button>"
                         "</section>"))
        window (.-window dom)
        document (.-document window)
        button (.querySelector document "#beans")
        scope (.querySelector document "#drag-scope")
        native-event (new (.-MouseEvent window)
                          "dragover"
                          #js {:bubbles true
                               :clientX 225
                               :clientY 31
                               :altKey true
                               :ctrlKey false
                               :metaKey false
                               :shiftKey true})
        context {:dom/event-name "dragover"
                 :pavlov-on-dragover ":grid/column-drag-over"
                 :pavlov-column-id "beans"
                 :matched-el scope}
        translator (get dom/built-in-default-translators "dragover")]
    (define-readonly! native-event "dataTransfer" (make-drag-data-transfer))
    (.dispatchEvent button native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :dom/dragover
              :dom/event-name "dragover"
              :pavlov-on-dragover :grid/column-drag-over
              :pavlov-column-id "beans"
              :dom/target {:id "beans"
                           :tag "button"
                           :type "button"}
              :dom/matched {:id "drag-scope"
                            :tag "section"}
              :dom/drag {:client-x 225
                         :client-y 31
                         :alt? true
                         :ctrl? false
                         :meta? false
                         :shift? true
                         :drop-effect "move"
                         :effect-allowed "move"
                         :types ["text/plain" "application/x.pavlov-column"]
                         :items [{:kind "string"
                                  :type "text/plain"}
                                 {:kind "string"
                                  :type "application/x.pavlov-column"}]}}
             (translator native-event context))))))

(deftest get-default-translators-includes-dragend-translator-for-drag-completion-metadata
  (let [dom (JSDOM. (str "<section id=\"drag-scope\""
                         "          pavlov-on-dragend=\":grid/column-drag-ended\""
                         "          pavlov-column-id=\"apples\">"
                         "  <button id=\"apples\" type=\"button\" draggable=\"true\">Apples</button>"
                         "</section>"))
        window (.-window dom)
        document (.-document window)
        button (.querySelector document "#apples")
        scope (.querySelector document "#drag-scope")
        native-event (new (.-MouseEvent window)
                          "dragend"
                          #js {:bubbles true
                               :clientX 310
                               :clientY 45
                               :altKey false
                               :ctrlKey false
                               :metaKey true
                               :shiftKey false})
        context {:dom/event-name "dragend"
                 :pavlov-on-dragend ":grid/column-drag-ended"
                 :pavlov-column-id "apples"
                 :matched-el scope}
        translator (get dom/built-in-default-translators "dragend")]
    (define-readonly! native-event "dataTransfer" (make-drag-data-transfer))
    (.dispatchEvent button native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :dom/dragend
              :dom/event-name "dragend"
              :pavlov-on-dragend :grid/column-drag-ended
              :pavlov-column-id "apples"
              :dom/target {:id "apples"
                           :tag "button"
                           :type "button"}
              :dom/matched {:id "drag-scope"
                            :tag "section"}
              :dom/drag {:client-x 310
                         :client-y 45
                         :alt? false
                         :ctrl? false
                         :meta? true
                         :shift? false
                         :drop-effect "move"
                         :effect-allowed "move"
                         :types ["text/plain" "application/x.pavlov-column"]
                         :items [{:kind "string"
                                  :type "text/plain"}
                                 {:kind "string"
                                  :type "application/x.pavlov-column"}]}}
             (translator native-event context))))))

(deftest get-default-translators-includes-dragleave-translator-for-drop-zone-exit-metadata
  (let [dom (JSDOM. (str "<section id=\"drag-scope\""
                         "          pavlov-on-dragleave=\":grid/column-drag-left\""
                         "          pavlov-column-id=\"beans\">"
                         "  <button id=\"beans\" type=\"button\">Beans</button>"
                         "</section>"))
        window (.-window dom)
        document (.-document window)
        button (.querySelector document "#beans")
        scope (.querySelector document "#drag-scope")
        native-event (new (.-MouseEvent window)
                          "dragleave"
                          #js {:bubbles true
                               :clientX 280
                               :clientY 39
                               :altKey false
                               :ctrlKey true
                               :metaKey false
                               :shiftKey false})
        context {:dom/event-name "dragleave"
                 :pavlov-on-dragleave ":grid/column-drag-left"
                 :pavlov-column-id "beans"
                 :matched-el scope}
        translator (get dom/built-in-default-translators "dragleave")]
    (define-readonly! native-event "dataTransfer" (make-drag-data-transfer))
    (.dispatchEvent button native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :dom/dragleave
              :dom/event-name "dragleave"
              :pavlov-on-dragleave :grid/column-drag-left
              :pavlov-column-id "beans"
              :dom/target {:id "beans"
                           :tag "button"
                           :type "button"}
              :dom/matched {:id "drag-scope"
                            :tag "section"}
              :dom/drag {:client-x 280
                         :client-y 39
                         :alt? false
                         :ctrl? true
                         :meta? false
                         :shift? false
                         :drop-effect "move"
                         :effect-allowed "move"
                         :types ["text/plain" "application/x.pavlov-column"]
                         :items [{:kind "string"
                                  :type "text/plain"}
                                 {:kind "string"
                                  :type "application/x.pavlov-column"}]}}
             (translator native-event context))))))

(deftest get-default-translators-includes-drop-translator-for-drop-target-and-transfer-metadata
  (let [dom (JSDOM. (str "<section id=\"drop-scope\">"
                         "  <button id=\"beans\""
                         "          type=\"button\""
                         "          pavlov-on-drop=\":grid/column-dropped\""
                         "          pavlov-column-id=\"beans\">Beans</button>"
                         "</section>"))
        window (.-window dom)
        document (.-document window)
        button (.querySelector document "#beans")
        native-event (new (.-MouseEvent window)
                          "drop"
                          #js {:bubbles true
                               :clientX 248
                               :clientY 28
                               :altKey false
                               :ctrlKey false
                               :metaKey false
                               :shiftKey true})
        context {:dom/event-name "drop"
                 :pavlov-on-drop ":grid/column-dropped"
                 :pavlov-column-id "beans"
                 :matched-el button}
        translator (get dom/built-in-default-translators "drop")]
    (define-readonly! native-event "dataTransfer" (make-drag-data-transfer))
    (.dispatchEvent button native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :dom/drop
              :dom/event-name "drop"
              :pavlov-on-drop :grid/column-dropped
              :pavlov-column-id "beans"
              :dom/target {:id "beans"
                           :tag "button"
                           :type "button"}
              :dom/matched {:id "beans"
                            :tag "button"}
              :dom/drag {:client-x 248
                         :client-y 28
                         :alt? false
                         :ctrl? false
                         :meta? false
                         :shift? true
                         :drop-effect "move"
                         :effect-allowed "move"
                         :types ["text/plain" "application/x.pavlov-column"]
                         :items [{:kind "string"
                                  :type "text/plain"}
                                 {:kind "string"
                                  :type "application/x.pavlov-column"}]}}
             (translator native-event context))))))

(deftest get-default-translators-includes-dragenter-translator-for-drop-zone-entry-metadata
  (let [dom (JSDOM. (str "<section id=\"drag-scope\""
                         "          pavlov-on-dragenter=\":grid/column-drag-entered\""
                         "          pavlov-column-id=\"beans\">"
                         "  <button id=\"beans\" type=\"button\">Beans</button>"
                         "</section>"))
        window (.-window dom)
        document (.-document window)
        button (.querySelector document "#beans")
        scope (.querySelector document "#drag-scope")
        native-event (new (.-MouseEvent window)
                          "dragenter"
                          #js {:bubbles true
                               :clientX 230
                               :clientY 30
                               :altKey false
                               :ctrlKey false
                               :metaKey true
                               :shiftKey true})
        context {:dom/event-name "dragenter"
                 :pavlov-on-dragenter ":grid/column-drag-entered"
                 :pavlov-column-id "beans"
                 :matched-el scope}
        translator (get dom/built-in-default-translators "dragenter")]
    (define-readonly! native-event "dataTransfer" (make-drag-data-transfer))
    (.dispatchEvent button native-event)
    (is (fn? translator))
    (when (fn? translator)
      (is (= {:type :dom/dragenter
              :dom/event-name "dragenter"
              :pavlov-on-dragenter :grid/column-drag-entered
              :pavlov-column-id "beans"
              :dom/target {:id "beans"
                           :tag "button"
                           :type "button"}
              :dom/matched {:id "drag-scope"
                            :tag "section"}
              :dom/drag {:client-x 230
                         :client-y 30
                         :alt? false
                         :ctrl? false
                         :meta? true
                         :shift? true
                         :drop-effect "move"
                         :effect-allowed "move"
                         :types ["text/plain" "application/x.pavlov-column"]
                         :items [{:kind "string"
                                  :type "text/plain"}
                                 {:kind "string"
                                  :type "application/x.pavlov-column"}]}}
             (translator native-event context))))))
