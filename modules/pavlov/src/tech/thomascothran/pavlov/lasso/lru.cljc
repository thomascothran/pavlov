(ns tech.thomascothran.pavlov.lasso.lru
  (:require [tech.thomascothran.pavlov.lasso.proto :refer [LassoDetector]])
  #?(:clj (:import [java.util LinkedHashMap])))

;; Guaranteed to execute in a single thread

#?(:clj
   (defn make-lru-map ^LinkedHashMap [capacity]
     (proxy [LinkedHashMap] [16 0.75 true]
       (removeEldestEntry [^java.util.Map$Entry _eldest]
         (> (.size ^LinkedHashMap this) (int capacity))))))

#?(:clj
   (defn make-lru-detector
     [capacity]
     (let [m  (make-lru-map capacity)
           !i (volatile! 0)]
       (reify LassoDetector
         (begin! [_] (vreset! !i 0) (.clear m))
         (observe! [_ key]
           (let [i (vswap! !i inc)]
             (if (.containsKey m key)
               (let [j (.get m key)]
                 (.put m key i)
                 {:repeat? true :period (- i (int j)) :key key})
               (do (.put m key i) nil))))
         (end! [_] nil)))))

;; TODO: this is guaranteed to be used on a single thread,
;; so we can use mutable data structures for better performance
#?(:cljs
   (defn make-lru-detector ^LassoDetector [capacity]
     (let [!idx   (atom 0)
           !queue (atom #queue [])
           !map   (atom {})] ; key -> first-index
       (reify LassoDetector
         (begin! [_]
           (reset! !idx 0)
           (reset! !queue #queue [])
           (reset! !map {}))
         (observe! [_ key]
           (let [i (swap! !idx inc)
                 m @!map]
             (if-let [j (get m key)]
               {:repeat? true :period (- i j) :key key}
               (do
                 (swap! !queue conj key)
                 (swap! !map assoc key i)
                 (when (> (count @!queue) capacity)
                   (let [old (peek @!queue)]
                     (swap! !queue pop)
                     (swap! !map dissoc old)))
                 nil))))
         (end! [_] nil)))))
