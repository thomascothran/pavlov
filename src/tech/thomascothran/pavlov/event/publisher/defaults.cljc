(ns tech.thomascothran.pavlov.event.publisher.defaults
  (:require [tech.thomascothran.pavlov.event.publisher.proto :as publisher]))

(defn- notify!
  [publisher event]
  (let [subscribers @(get publisher :subscribers)]
    (tap> [::notify! {:event event
                      :subscribers subscribers}])
    (doseq [[k listener] subscribers]
      (try (listener event)
           (catch #?(:clj Throwable :cljs :default) _
             (swap! subscribers dissoc k))))))

(defn- subscribe!
  [publisher k f]
  (swap! (get publisher :subscribers) assoc k f))

(defn make-publisher!
  [opts]
  (with-meta {:subscribers (-> (get opts :subscribers {}) atom)}
    {`publisher/start! (fn [_])
     `publisher/stop! (fn [_])
     `publisher/notify! notify!
     `publisher/subscribe! subscribe!
     `publisher/subscribers! #(-> (get % :subscribers) deref)}))
