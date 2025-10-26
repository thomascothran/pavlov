(ns ^:alpha tech.thomascothran.pavlov.viz.cytoscape-html
  "Generate Cytoscape HTML from Pavlov bthreads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [tech.thomascothran.pavlov.viz.cytoscape :as cytoscape]))

(def ^:private template-resource "viz/cytoscape/shell.html")
(def ^:private template-fallback
  (str "modules/pavlov-devtools/resources/" template-resource))

(def ^:private data-placeholder "__PAVLOV_CY_DATA__")

(defn- fetch-template []
  (if-let [resource (io/resource template-resource)]
    (slurp resource)
    (let [file (io/file template-fallback)]
      (when (.exists file)
        (slurp file)))))

(defn- load-template []
  (or (fetch-template)
      (throw (ex-info "Cytoscape HTML template not found"
                      {:template template-resource
                       :fallback template-fallback}))))

(defn -graph
  "Convert a Pavlov graph structure to Cytoscape-friendly data."
  [graph-map]
  (cytoscape/-graph->cytoscape graph-map))

(defn ->body
  "Inject Cytoscape data into the template's body segment."
  ([cy-data]
   (->body cy-data (load-template)))
  ([cy-data template]
   (let [payload (json/write-value-as-string cy-data)]
     (str/replace template data-placeholder payload))))

(defn ->page
  "Produce a full HTML page string for the supplied Cytoscape data map."
  ([cy-data]
   (->page cy-data (load-template)))
  ([cy-data template]
   (->body cy-data template)))

(defn ->html
  "Return a browser-ready Cytoscape HTML document for `bthreads`."
  [bthreads]
  (-> bthreads
      cytoscape/graph->cytoscape
      ->page))

(comment
  (require '[tech.thomascothran.pavlov.bthread :as b])

  (defn make-bthreads
    []
    {:linear (b/bids [{:request #{:begin}}
                      {:request #{{:type :step-1}}}
                      {:request #{{:type :step-2
                                   :invariant-violated true}}}
                      {:request #{{:type :step-3
                                   :environment true}}}
                      {:request #{{:type :step-4
                                   :terminal true}}}])})

  (spit "cytoscape-test.html" (->html (make-bthreads)))
  ;; Produces a full HTML page containing serialized Cytoscape data.
  (subs (->html (make-bthreads)) 0 200))
