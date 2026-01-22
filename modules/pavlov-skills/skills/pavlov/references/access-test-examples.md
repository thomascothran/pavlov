# Access Pavlov test examples

Use `clojure.tools.namespace.find` with `clojure.java.classpath` to list test sources available on the active classpath (directories and jars).

Requires deps: `org.clojure/tools.namespace` and `org.clojure/java.classpath`.

## List test sources

```clojure
(require '[clojure.java.classpath :as cp]
         '[clojure.tools.namespace.find :as find]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(import '[java.util.jar JarFile])

(defn test-sources []
  (->> (cp/classpath)
       (mapcat (fn [entry]
                 (cond
                   (.isDirectory entry) (find/find-sources-in-dir entry)
                   (and (.isFile entry) (.endsWith (.getName entry) ".jar"))
                   (find/sources-in-jar (JarFile. entry))
                   :else [])))
       (map str)
       (filter (fn [path]
                 (and (str/includes? path "tech/thomascothran/pavlov")
                      (or (str/ends-with? path "_test.clj")
                          (str/ends-with? path "_test.cljc")
                          (str/ends-with? path "_test.cljs")))))))

(take 10 (test-sources))
```

## Read a test

```clojure
(slurp (io/resource "tech/thomascothran/pavlov/bthread_test.cljc"))
(slurp (io/resource "tech/thomascothran/pavlov/bprogram/ephemeral_test.clj"))
```
