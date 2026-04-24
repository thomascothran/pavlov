# State and environment bthreads

Use separate namespaces for bthreads that simulate external systems in tests. Two common kinds are state stubs and environment bthreads.

## State stubs

State stub bthreads mimic stateful collaborators such as repositories, databases, or HTTP services. They often create branching by requesting multiple possible result events.

```clojure
(ns test.your.domain.foo-store
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn- make-find-foo
  []
  (b/bids [{:wait-on #{:foo/find}}
           (fn [{foo-id :foo/id}]
             {:request #{{:type :foo/found
                          :foo/id foo-id
                          :foo/color :red}
                         {:type :foo/found
                          :foo/id foo-id
                          :foo/color :purple}
                         {:type :foo/not-found
                          :foo/id foo-id}}})]))

(defn make-bthreads
  []
  {:test.your.domain.foo-store/find-foo (make-find-foo)})
```

## Environment bthreads

Environment bthreads request events spontaneously. Use them to model initiating requests, user actions, timers, or incoming Kafka messages. Sets are useful because they establish branching in the state space.

```clojure
(ns test.your.domain.environment
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn- make-init-requests
  []
  (b/bids [{:request #{{:type :workflow/initialize-workflow-a
                        :foo/id 1}
                       {:type :workflow/initialize-workflow-a
                        :foo/id 2}}}]))

(defn- make-user-actions
  []
  (let [user-actions #{{:type :action/bar}
                       {:type :action/baz}}]
    (b/repeat 3 {:request user-actions})))

(defn- kafka-messages
  []
  #{{:type :kafka/foo-received}
    {:type :kafka/bar-received}})

(defn- make-simulate-kafka-messages
  []
  (b/repeat 3 {:request (kafka-messages)}))

(defn make-bthreads
  []
  {:test.your.domain.environment/init-requests           (make-init-requests)
   :test.your.domain.environment/user-actions            (make-user-actions)
   :test.your.domain.environment/simulate-kafka-messages (make-simulate-kafka-messages)})
```

Keep these namespaces in test code unless they are part of the real runtime behavior you are intentionally modeling.
