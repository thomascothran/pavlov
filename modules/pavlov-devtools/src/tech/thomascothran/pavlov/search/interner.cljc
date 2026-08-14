(ns tech.thomascothran.pavlov.search.interner
  (:refer-clojure :exclude [intern]))

(defn make
  "Creates an empty structural interner with a local ID domain."
  []
  {:state-key->id {}
   :next-id 0})

(defn intern
  "Returns [updated-interner {:id n :new? boolean}] for key.

  Structurally equal immutable keys reuse an ID. Distinct keys receive dense,
  monotonically increasing IDs local to this interner. The input interner is
  not modified."
  [{:keys [state-key->id next-id] :as interner} key]
  (if-let [entry (find state-key->id key)]
    [interner {:id (val entry) :new? false}]
    [(-> interner
         (assoc-in [:state-key->id key] next-id)
         (update :next-id inc))
     {:id next-id :new? true}]))
