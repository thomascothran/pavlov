(ns tech.thomascothran.pavlov.explorer.page
  "HTML for the explorer. Every page is built from plain data: the catalogue
  entries, or a program (see `program/build`) and a position (see
  `position/locate`)."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as c]
            [tech.thomascothran.pavlov.explorer.program :as program]))

;; ---------------------------------------------------------------------------
;; Addresses

(defn href
  "Address of a position: a program id, the fingerprints of the trace, and
  whether the bthread view is open."
  ([program-id] (href program-id [] nil))
  ([program-id trace {:keys [bthreads?]}]
   (str "/p/" program-id
        (when (seq trace) (str "/" (str/join "." trace)))
        (when bthreads? "?bthreads=1"))))

;; ---------------------------------------------------------------------------
;; Events and names

(def ^:private flag-keys
  "Keys Pavlov reads from an event. They are reported in words where they
  matter, so they are left out of an event's data rows."
  [:type :terminal :invariant-violated])

(defn- pretty
  [x width]
  (binding [pprint/*print-right-margin* width]
    (str/trimr (with-out-str (pprint/pprint x)))))

(defn- fields
  [event]
  (when (map? event)
    (apply dissoc event flag-keys)))

(defn- name-html
  [bthread-name]
  (let [s (pr-str bthread-name)
        i (str/last-index-of s "/")]
    (if i
      (list [:span.ns (subs s 0 (inc i))] (subs s (inc i)))
      s)))

(def ^:private role-label
  {:rule "rule" :environment "environment" :safety "safety check" :spawned "spawned"})

(defn- pill
  [role]
  [:span {:class (str "pill " (name role))} (role-label role)])

(defn- role-of
  [program bthread-name]
  (get-in program [:roles bthread-name] :spawned))

(defn- event-line
  "An event on one line: its type, then its data, cut short if long."
  [{:keys [event type]}]
  (let [data (str/join " " (map (fn [[k v]] (str (pr-str k) " " (pr-str v)))
                                (fields event)))]
    (list [:span.ev-type (pr-str type)]
          (when (seq data) [:span.ev-data data]))))

(defn- fields-html
  "One row per key of a map event. Compact rows keep each value on one line;
  full rows pretty-print nested values."
  [{:keys [event]} full?]
  (when-let [fs (seq (fields event))]
    [:div {:class (str "fields" (when full? " full"))}
     (for [[k v] fs]
       (list [:span.k (pr-str k)]
             [:span {:class (str "v" (when (coll? v) " nested"))}
              (if full? (pretty v 44) (pr-str v))]))]))

(defn- requesters
  [program names]
  (interpose " "
             (for [n names]
               (list (pill (role-of program n)) " " [:span.mono (name-html n)]))))

;; ---------------------------------------------------------------------------
;; Page shell

(defn- shell
  [title & body]
  (str
   (c/html
    [c/doctype-html5
     [:html {:lang "en"}
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
       [:title title]
       [:link {:rel "stylesheet" :href "/assets/explorer.css"}]]
      [:body body [:script {:src "/assets/explorer.js"}]]]])))

(defn- topbar
  [crumb & tools]
  [:header.topbar
   [:a.brand {:href "/"} "PAVLOV_EXPLORER"]
   [:div.crumbs [:a {:href "/"} "Catalogue"] (when crumb (list " / " [:b crumb]))]
   (when (seq tools) [:div.tools tools])])

;; ---------------------------------------------------------------------------
;; Catalogue

(defn catalogue
  "The list of programs on offer. `notice` is shown above the list when set."
  [entries notice]
  (shell
   "Pavlov Explorer"
   (topbar nil)
   [:div.catalogue
    (when notice
      [:div.banner.notice [:h3 "Program not available"] [:p notice]])
    [:div.label.green "■ Programs on offer"]
    [:h1 "CHOOSE A " [:span "PROGRAM"]]
    [:p.muted "Pick a program to walk through it one event at a time."]
    [:div.cards
     (if (seq entries)
       (for [entry entries]
         [:div.card
          [:h3 (:name entry)]
          [:p (:description entry)]
          [:a.btn.primary {:href (href (program/program-id entry))} "Explore"]])
       [:p.muted "There are no programs to explore."])]]))

;; ---------------------------------------------------------------------------
;; Program page (the Trail)

(defn- reload-form
  [program-id trace opts]
  [:form {:method "post" :action (str "/p/" program-id "/reload")}
   [:input {:type "hidden" :name "trace" :value (str/join "." trace)}]
   (when (:bthreads? opts) [:input {:type "hidden" :name "bthreads" :value "1"}])
   [:button.btn {:type "submit" :title "Rebuild this program from its current code"}
    "Reload program"]])

(defn- trail
  [program-id {:keys [steps trace]} opts]
  (let [n (count steps)]
    [:section.panel.trail-panel
     [:h2 "How I got here"]
     [:ol.trail
      [:li {:class (when (zero? n) "here")}
       [:span.dot "▶"]
       [:a.step.start {:href (href program-id [] opts)} "START"]]
      (map-indexed
       (fn [k {:keys [edge options]}]
         (let [others (remove #(= edge %) options)
               before (subvec trace 0 k)]
           [:li {:class (when (= k (dec n)) "here")}
            [:span.dot (inc k)]
            [:a.step {:href (href program-id (subvec trace 0 (inc k)) opts)
                      :title "Go back to this point"}
             (event-line edge)]
            (if (seq others)
              [:details.alts {:open (= k (dec n))}
               [:summary (count others) (if (= 1 (count others)) " alternative" " alternatives")]
               (for [other others]
                 [:a.alt {:href (href program-id (conj before (:fingerprint other)) opts)
                          :title "Switch to this instead"}
                  [:span.dim "instead: "] (event-line other)])]
              [:div.alts.dim "no alternatives"])]))
       steps)]]))

(defn- here-card
  [program {:keys [steps]}]
  [:section.panel.bracket.herecard
   (if-let [{:keys [event type requested-by] :as edge} (:edge (peek steps))]
     (list [:div.label "Step " (count steps) " · what just happened"]
           [:div.big [:span.ev-type (pr-str type)]]
           (cond
             (not (map? event)) [:div.kw "This event carries no data."]
             (empty? (fields event)) [:div.kw "This event carries no other data."]
             :else (fields-html edge true))
           [:div.by.muted "requested by " (requesters program requested-by)])
     (list [:div.label "You are at"]
           [:div.big [:span.ev-type "THE START"]]
           [:div.muted (:description program)]))])

(defn- step-name
  [steps k]
  (if (zero? k)
    "the start"
    (str "step " k " (" (pr-str (:type (:edge (nth steps (dec k))))) ")")))

(defn- banner
  [program {:keys [steps trace]} opts {:keys [kind title text seen-at reported-by]}]
  [:div {:class (str "banner " (name kind))}
   [:h3 title]
   [:p (case kind
         :revisit (list "The program is in the same situation it was in at "
                        [:a {:href (href (:id program) (subvec trace 0 seen-at) opts)}
                         (step-name steps seen-at)]
                        ".")
         :violation (list "Reported by the safety check "
                          (requesters program reported-by) ".")
         text)]])

(defn- choices
  [program-id program {:keys [choices trace]} opts]
  [:section.panel
   [:h2 "What can happen next" (when (seq choices) (str " · " (count choices)))]
   (if (seq choices)
     (for [{:keys [event type fingerprint requested-by] :as edge} choices]
       [:div.choice-wrap
        [:a.choice {:href (href program-id (conj trace fingerprint) opts)}
         [:span.ev-type (pr-str type)]
         (fields-html edge false)
         [:div.by "requested by " (requesters program requested-by)]]
        [:details.more
         [:summary "details"]
         [:div.edn (pretty event 56)]]])
     [:p.dim "No choices."])])

(defn- chips
  [xs kind]
  (if (seq xs)
    (for [x (sort (map pr-str xs))]
      [:span {:class (str "chip " kind)} x])
    [:span.dim "nothing"]))

(defn- bthread-item
  [program {:keys [changed]} bthread-name {:keys [state request wait-on block]} index]
  (let [finished? (and (empty? request) (empty? wait-on) (empty? block))
        changed? (contains? changed bthread-name)
        source (get-in program [:sources bthread-name])
        dialog-id (str "code-" index)]
    [:details {:class (str "bt" (when changed? " changed"))
               :data-keep (str (:id program) "/" (pr-str bthread-name))}
     [:summary
      [:div.bt-name (name-html bthread-name)]
      [:div.bt-sum
       (when changed? [:span.pill.changed "changed by last event"])
       (if finished?
         [:span.pill.done "finished"]
         (str/join " · "
                   (remove nil?
                           [(when (seq request) (str "requesting " (count request)))
                            (when (seq wait-on) (str "waiting for " (count wait-on)))
                            (when (seq block) (str "blocking " (count block)))])))]]
     [:dl.bt-body
      [:dt.label "Requesting"] [:dd (chips request "req")]
      [:dt.label "Waiting for"] [:dd (chips wait-on "wait")]
      [:dt.label "Blocking"] [:dd (chips block "block")]
      (when-not finished?
        (list [:dt.label "Internal state"] [:dd [:span.chip.state (pr-str state)]]))
      (when source
        (list
         [:dd.code-link [:button.btn {:type "button" :data-dialog dialog-id} "View code"]]
         [:dialog.code {:id dialog-id}
          [:h2 [:span.mono (name-html bthread-name)] " " (pill (role-of program bthread-name))
           [:button.btn {:type "button" :data-close true} "Close"]]
          [:pre source]]))]]))

(defn- drawer
  [program {:keys [bthreads trace] :as position}]
  (let [;; bthreads that have finished are no longer at the node; they are
        ;; still listed, with nothing left to do
        bthreads (merge (zipmap (keys (:roles program)) (repeat {})) bthreads)
        by-role (group-by #(role-of program (key %)) bthreads)
        index (zipmap (keys bthreads) (range))]
    [:aside.drawer
     [:h2 "Bthreads" [:a.btn {:href (href (:id program) trace nil)} "Close"]]
     [:p.muted "What each part of the program is asking for, waiting for and preventing at your position."]
     (for [role program/role-order
           :let [items (sort-by (comp pr-str key) (get by-role role))]
           :when (seq items)]
       (list [:div.group (pill role)]
             (for [[bthread-name doing] items]
               (bthread-item program position bthread-name doing (index bthread-name)))))]))

(defn program
  "The page for one position in a program. `opts` may set `:bthreads?` to show
  the bthread view."
  [program {:keys [steps trace] :as position} opts]
  (let [id (:id program)
        back? (seq steps)]
    (shell
     (str (:name program) " · Pavlov Explorer")
     (topbar
      (:name program)
      (if back?
        (list [:a.btn {:href (href id (pop trace) opts)} "◀ Step back"]
              [:a.btn {:href (href id [] opts)} "Start over"])
        (list [:span.btn.disabled "◀ Step back"]
              [:span.btn.disabled "Start over"]))
      [:button.btn {:type "button" :data-copy true} "Copy link"]
      (reload-form id trace opts)
      [:a {:class (str "btn" (when (:bthreads? opts) " on"))
           :href (href id trace (update opts :bthreads? not))}
       "Bthreads"])
     [:div.frame
      [:main.stage
       [:div.a-grid
        (trail id position opts)
        [:div
         (here-card program position)
         (for [b (:banners position)]
           (banner program position opts b))
         (choices id program position opts)]]]
      (when (:bthreads? opts) (drawer program position))])))

(defn failed
  "Shown when a program could not be built from its current code."
  [entry message]
  (let [id (program/program-id entry)]
    (shell
     (str (:name entry) " · Pavlov Explorer")
     (topbar (:name entry) (reload-form id [] nil))
     [:div.catalogue
      [:div.banner.stuck
       [:h3 "This program could not be loaded"]
       [:p "Building it from its current code failed. Fix the code, then reload the program."]]
      [:div.edn message]])))
