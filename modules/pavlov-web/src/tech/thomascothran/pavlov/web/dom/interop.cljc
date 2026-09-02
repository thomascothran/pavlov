(ns tech.thomascothran.pavlov.web.dom.interop)

(defn collection-seq
  "Returns the items in a browser collection in iteration order.

  Browser collections are commonly array-like rather than ClojureScript
  seqable. Squint can consume their JavaScript iterator directly, while full
  ClojureScript uses `array-seq`. Nil consistently produces an empty sequence."
  [collection]
  (when collection
    #?(:squint (seq (js/Array.from collection))
       :cljs (array-seq (js/Array.from collection))
       :clj (seq collection))))
