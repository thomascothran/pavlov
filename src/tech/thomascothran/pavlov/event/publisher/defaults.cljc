(ns tech.thomascothran.pavlov.event.publisher.defaults
  (:require [tech.thomascothran.pavlov.event.publisher.proto :as publisher]))

(defn- -notify!
  [!subscribers event bthread->bid]
  (doseq [[k subscriber] @!subscribers]
    (try (subscriber event bthread->bid)
         (catch #?(:clj Throwable :cljs :default) _
           (swap! !subscribers dissoc k)))))

(defn- -subscribe!
  [!subscribers k f]
  (swap! !subscribers assoc k f))

(defn make-publisher!
  [opts]
  (let [!subscribers (-> (get opts :subscribers {}) atom)]
    (reify publisher/Publisher
      (start! [_])
      (stop! [_])
      (notify! [_ event bthread->bid]
        (-notify! !subscribers event bthread->bid))
      (subscribe! [_ k f] (-subscribe! !subscribers k f)))))
