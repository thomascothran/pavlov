(ns pavlov-web-example.browser-only.client.datagrid.in-memory
  (:require [clojure.string :as str]
            [tech.thomascothran.pavlov.bthread :as b]))

(def initial-grid-sort-state
  {"telemetry" {:key "node-id"
                 :direction "asc"}})

(defn- sort-attr-key
  [event]
  (keyword (str "pavlov-" (:pavlov-sort-key event) "-sort-value")))

(defn- parse-sort-value
  [sort-type value]
  (case sort-type
    "number" (let [parsed (js/parseFloat value)]
                 (if (js/isNaN parsed)
                   js/Number.POSITIVE_INFINITY
                   parsed))
    (or value "")))

(defn- compare-values
  [left right]
  (compare left right))

(defn- sort-direction-multiplier
  [direction]
  (if (= direction "desc")
    -1
    1))

(defn- next-sort-direction
  [previous-sort event]
  (let [sort-key (:pavlov-sort-key event)
        default-direction (:pavlov-sort-default-direction event "asc")]
    (if (= sort-key (:key previous-sort))
      (if (= "desc" (:direction previous-sort)) "asc" "desc")
      default-direction)))

(defn- row-sort-value
  [row sort-attr]
  (get row sort-attr))

(defn- row-comparator
  [sort-attr sort-type direction]
  (let [direction* (sort-direction-multiplier direction)]
    (fn [left right]
      (let [left-value (parse-sort-value sort-type (row-sort-value left sort-attr))
            right-value (parse-sort-value sort-type (row-sort-value right sort-attr))
            value-result (* direction* (compare-values left-value right-value))]
        (if (zero? value-result)
          (compare-values (:index left)
                          (:index right))
          value-result)))))

(defn- captured-row?
  [child]
  (and (= "tr" (:tag child))
       (seq (:id child))))

(defn- captured-sort-cell?
  [sort-attr child]
  (and (= "td" (:tag child))
       (contains? child sort-attr)))

(defn- captured-rows
  [event sort-attr]
  (let [{:keys [rows current-row]}
        (reduce (fn [{:keys [rows current-row] :as state} child]
                  (cond
                    (captured-row? child)
                    {:rows (cond-> rows
                              current-row
                              (conj current-row))
                     :current-row {:id (:id child)}}

                    (and current-row (captured-sort-cell? sort-attr child))
                    (assoc-in state
                              [:current-row sort-attr]
                              (get child sort-attr))

                    :else state))
                {:rows []
                 :current-row nil}
                (:dom/children event))]
    (->> (cond-> rows
           current-row
           (conj current-row))
         (map-indexed (fn [index row]
                        (assoc row :index index)))
         vec)))

(defn- ordered-row-ids
  [event direction]
  (let [sort-attr (sort-attr-key event)
        sort-type (:pavlov-sort-type event)]
    (->> (captured-rows event sort-attr)
         (sort (row-comparator sort-attr sort-type direction))
         (mapv :id))))

(defn- reorder-grid-dom-op
  [event direction]
  (when-let [child-ids (seq (ordered-row-ids event direction))]
    {:type :pavlov.web.dom/op
     :selector (:pavlov-grid-body-selector event)
     :kind :reorder-children
     :child-ids child-ids}))

(defn- normalize-search-query
  [value]
  (-> (or value "")
      str/trim
      str/lower-case))

(defn- row-matches-search?
  [query row]
  (or (empty? query)
      (str/includes? (normalize-search-query (:pavlov-search-value row))
                     query)))

(defn- set-row-hidden-dom-op
  [row-id hidden?]
  {:type :pavlov.web.dom/op
   :selector (str "#" row-id)
   :kind :set
   :member "hidden"
   :value hidden?})

(defn- grid-search-dom-ops
  [event]
  (let [query (normalize-search-query (get-in event [:dom/input :value]))]
    (->> (:dom/children event)
         (filter captured-row?)
         (mapv (fn [row]
                 (set-row-hidden-dom-op (:id row)
                                        (not (row-matches-search? query row))))))))

(defn- grid-search-dom-event
  [event]
  {:type :pavlov.web.dom/ops
   :ops (grid-search-dom-ops event)})

(defn- grid-sort-button-selector
  [grid-id]
  (str "[pavlov-grid-id=\"" grid-id
       "\"][pavlov-on-click=\":grid/sort-clicked\"]"))

(defn- active-grid-sort-button-selector
  [grid-id sort-key]
  (str (grid-sort-button-selector grid-id)
       "[pavlov-sort-key=\"" sort-key "\"]"))

(defn- set-sort-direction-dom-op
  [selector direction]
  {:type :pavlov.web.dom/op
   :selector selector
   :kind :call
   :member "setAttribute"
   :args ["data-sort-direction" direction]})

(defn- grid-sort-dom-ops
  [event direction]
  (let [grid-id (:pavlov-grid-id event)
        sort-key (:pavlov-sort-key event)
        reorder-op (reorder-grid-dom-op event direction)]
    (cond-> [(set-sort-direction-dom-op (grid-sort-button-selector grid-id) "none")
             (set-sort-direction-dom-op (active-grid-sort-button-selector grid-id sort-key)
                                        direction)]
      reorder-op (conj reorder-op))))

(defn- grid-sort-dom-event
  [event direction]
  {:type :pavlov.web.dom/ops
   :ops (grid-sort-dom-ops event direction)})

(defn- make-grid-sort-bthread
  []
  (b/step (fn [sort-state event]
            (let [sort-state' (or sort-state initial-grid-sort-state)]
              (if-not (= :grid/sort-clicked (:type event))
                [sort-state' {:wait-on #{:grid/sort-clicked}}]
                (let [grid-id (:pavlov-grid-id event)
                      previous-sort (get sort-state' grid-id)
                      direction (next-sort-direction previous-sort event)
                      next-sort {:key (:pavlov-sort-key event)
                                 :direction direction}]
                  [(assoc sort-state' grid-id next-sort)
                    {:wait-on #{:grid/sort-clicked}
                     :request #{(grid-sort-dom-event event direction)}}]))))))

(defn- make-grid-search-bthread
  []
  (b/on-any #{:grid/search-input}
            (fn [event]
              {:request #{(grid-search-dom-event event)}})))

(defn make-bthreads
  []
  [[:grid-search
    (make-grid-search-bthread)]
   [:grid-sort
    (make-grid-sort-bthread)]])
