(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'tech.thomascothran/pavlov-skills)
;; alternatively, use MAJOR.MINOR.COMMITS:
(def version (format "3.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def doc-dir (str class-dir "/tech/thomascothran/pavlov-skills/doc"))
(def skills-dir (str class-dir "/skills"))

(defn- pom-template [version]
  [[:description "Pavlov Skills"]
   [:url "https://github.com/thomascothran/pavlov"]
   [:licenses
    [:license
     [:name "Eclipse Public License"]
     [:url "http://www.eclipse.org/legal/epl-v10.html"]]]
   [:developers
    [:developer
     [:name "Thomas Cothran"]]]
   [:scm
    [:url "https://github.com/thomascothran/pavlov"]
    [:connection "scm:git:https://github.com:thomascothran/pavlov.git"]
    [:developerConnection "scm:git:ssh:git@github.com:thomascothran/pavlov.git"]
    [:tag (str "v" version)]]])

(defn- jar-opts [opts]
  (assoc opts
         :lib lib   :version version
         :jar-file  (format "target/%s-%s.jar" lib version)
         :basis     (b/create-basis {})
         :class-dir class-dir
         :target    "target"
         :src-dirs  ["src"]
         :pom-data  (pom-template version)))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  ;; (test opts)
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying resources...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (b/copy-dir {:src-dirs ["../../doc"] :target-dir doc-dir})
    (b/copy-file {:src "../../README.md"
                  :target (str doc-dir "/README.md")})
    (b/copy-dir {:src-dirs ["skills"] :target-dir skills-dir})
    (b/copy-dir {:src-dirs ["../pavlov/test" "../pavlov-devtools/test"]
                 :target-dir class-dir})
    (println "\nBuilding JAR..." (:jar-file opts))
    (b/jar opts))
  opts)

(defn install "Install the JAR locally." [opts]
  (let [opts (jar-opts opts)]
    (b/install opts))
  opts)

(defn deploy "Deploy the JAR to Clojars." [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
