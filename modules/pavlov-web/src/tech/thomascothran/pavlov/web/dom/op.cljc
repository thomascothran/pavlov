(ns tech.thomascothran.pavlov.web.dom.op
  "Private DOM op implementation namespace. This namespace is intended for use
  only by `tech.thomascothran.pavlov.web.dom`."
  (:require [tech.thomascothran.pavlov.event :as event]))

#?(:cljs
   (defn- call-member!
     [dom-node member args]
     (.apply (aget dom-node member) dom-node (into-array args))))

#?(:cljs
   (defn- reorder-children!
     [dom-node child-ids]
     (let [children (array-seq (.-children dom-node))
           children-by-id (reduce (fn [acc child]
                                    (let [child-id (.-id child)]
                                      (if (seq child-id)
                                        (assoc acc child-id child)
                                        acc)))
                                  {}
                                  children)
           ordered-children (keep children-by-id child-ids)]
       (call-member! dom-node
                     "replaceChildren"
                     ordered-children))))

#?(:cljs
   (defn- query-dom-nodes
     [query-selector selector]
     (if selector
       (array-seq (query-selector selector))
       [])))

#?(:cljs
   (defn- run-node-op!
     [dom-node op]
     (let [kind (get op :kind)
           member (get op :member)
           args (when (= :call kind)
                  (get op :args))
           child-ids (when (= :reorder-children kind)
                       (get op :child-ids))
           value (when (= :set kind)
                   (get op :value))]
       (case kind
         :set (unchecked-set dom-node member value)
         :call (call-member! dom-node member args)
         :reorder-children (reorder-children! dom-node child-ids)
         nil))))

#?(:cljs
   (defn- apply-local-mutation!
     [fragment op]
     (doseq [dom-node (query-dom-nodes #(.querySelectorAll fragment %) (get op :local-selector))]
       (run-node-op! dom-node op))))

#?(:cljs
   (defn- attach-fragment!
     [query-selector fragment attach]
     (let [selector (get attach :selector)
           position (get attach :position :append)]
       (doseq [dom-node (query-dom-nodes query-selector selector)]
         (let [fragment' (.cloneNode fragment true)]
           (case position
             :prepend (call-member! dom-node "prepend" [fragment'])
             :replace-children (call-member! dom-node "replaceChildren" [fragment'])
             (call-member! dom-node "append" [fragment'])))))))

#?(:cljs
   (defn- instantiate-fragment!
     [query-selector op]
     (let [source (get op :source)
           source-kind (get source :kind)
           source-selector (get source :selector)
           mutations (get op :mutations [])
           attach (get op :attach)]
       (when (= :template source-kind)
         (doseq [template-node (query-dom-nodes query-selector source-selector)]
           (let [fragment (.cloneNode (.-content template-node) true)]
             (doseq [mutation mutations]
               (apply-local-mutation! fragment mutation))
             (attach-fragment! query-selector fragment attach)))))))

(defn -run-op!
  [query-selector event]
  #?(:cljs
     (let [selector (get event :selector)
           kind (get event :kind)]
       (if (= :instantiate-fragment kind)
         (instantiate-fragment! query-selector event)
         (doseq [dom-node (query-dom-nodes query-selector selector)]
           (run-node-op! dom-node event))))
     :clj nil))

(defn- event->ops
  [event]
  (case (event/type event)
    :pavlov.web.dom/op [event]
    :pavlov.web.dom/ops (get event :ops [])
    []))

(defn -run-ops!
  [query-selector event]
  (doseq [op (event->ops event)]
    (-run-op! query-selector op)))
