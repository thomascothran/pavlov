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
  (let [resource (io/resource template-resource)]
    (slurp resource)))

(defn- load-template []
  (or (fetch-template)
      (throw (ex-info "Cytoscape HTML template not found"
                      {:template template-resource
                       :fallback template-fallback}))))

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

(defn lts->html
  "Return a browser-ready Cytoscape HTML document for an LTS."
  [lts]
  (-> lts
      cytoscape/lts->cytoscape
      ->page))

(comment
  (do (require '[tech.thomascothran.pavlov.bthread :as b])

      (defn make-bthreads
        []
        {:linear (b/bids [{:request #{:begin}}
                          {:request #{{:type :step-1a}
                                      {:type :step-1b}}}
                          {:request #{{:type :step-2
                                       :invariant-violated true}}}
                          {:request #{{:type :step-3
                                       :environment true}}}
                          {:request #{{:type :step-4
                                       :terminal true}}}])})))
