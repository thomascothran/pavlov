(ns tech.thomascothran.pavlov.web.fetch-test
  (:require [cljs.test :refer [async deftest is]]
            [tech.thomascothran.pavlov.bprogram :as bp]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
            [tech.thomascothran.pavlov.bprogram.proto :as proto]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.io :as io]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.web.fetch :as fetch]))

(def request-event
  {:type :pavlov.web.fetch/request
   :request/id "request-123"
   :url "/tasks"
   :fetch-opts {:method "POST"}
   :in-flight-event-type :task-form/submit-pending
   :response-event-type :task-form/submit-response
   :error-event-type :task-form/submit-error})

(defn recording-program [!events]
  (reify proto/BProgram
    (submit-event! [_ event] (swap! !events conj event))))

(defn fake-response
  [{:keys [status ok headers body-json]}]
  #js {:status status
       :ok ok
       :headers (js/Headers. (clj->js headers))
       :json (fn []
               (js/Promise.resolve (js/JSON.parse body-json)))})

(defn flush-async! [f]
  (js/setTimeout f 0))

(deftest make-fetch-bthread-only-requests-in-flight-event
  (let [bthread (fetch/make-fetch-bthread)]
    (is (= {:wait-on #{:pavlov.web.fetch/request}}
           (b/notify! bthread nil)))
    (is (= {:wait-on #{:pavlov.web.fetch/request}
            :request #{{:type :task-form/submit-pending
                        :request/id "request-123"}}
            :block #{} :hot nil :bthreads nil}
           (b/notify! bthread request-event)))))

(deftest fetch-subscriber-routes-requests-through-io-dispatch
  (let [!tasks (atom [])
        !calls (atom [])
        !events (atom [])
        program (recording-program !events)
        subscriber (io/make-subscriber!
                    {:pavlov.web.fetch/request
                     (fetch/make-fetch-handler
                      (fn [& args]
                        (swap! !calls conj args)
                        (js/Promise. (fn [_ _] nil))))}
                    {:dispatch! #(swap! !tasks conj %)})]
    (is (nil? (subscriber {:type :unrelated} program)))
    (is (empty? @!tasks))
    (is (nil? (subscriber request-event program)))
    (is (= 1 (count @!tasks)))
    (is (empty? @!calls) "Dispatch owns effect initiation")
    ((first @!tasks))
    (is (= [["/tasks" {:method "POST"}]] @!calls))
    (is (empty? @!events) "Pending state belongs to the bthread, not the handler")))

(defn check-outcome! [fetch! expected done]
  (let [!events (atom [])
        subscriber (io/make-subscriber!
                    {:pavlov.web.fetch/request (fetch/make-fetch-handler fetch!)})]
    (subscriber request-event (recording-program !events))
    (flush-async!
     (fn []
       (is (= [expected] @!events))
       (done)))))

(deftest fetch-subscriber-submits-response-event-for-2xx
  (async done
         (check-outcome!
          (fn [_ _]
            (js/Promise.resolve
             (fake-response {:status 201 :ok true
                             :headers {"content-type" "application/json"}
                             :body-json "{\"task/id\":123,\"task-name\":\"Take out trash\"}"})))
          {:type :task-form/submit-response :request/id "request-123"
           :status 201 :ok true :headers {"content-type" "application/json"}
           :body {:task/id 123 :task-name "Take out trash"}}
          done)))

(deftest fetch-subscriber-submits-response-event-for-4xx
  (async done
         (check-outcome!
          (fn [_ _]
            (js/Promise.resolve
             (fake-response {:status 422 :ok false
                             :headers {"content-type" "application/json"}
                             :body-json "{\"errors\":{\"task-name\":[\"Too short\"]}}"})))
          {:type :task-form/submit-response :request/id "request-123"
           :status 422 :ok false :headers {"content-type" "application/json"}
           :body {:errors {:task-name ["Too short"]}}}
          done)))

(deftest fetch-subscriber-submits-error-for-network-rejection
  (async done
         (check-outcome!
          (fn [_ _] (js/Promise.reject (js/Error. "network unavailable")))
          {:type :task-form/submit-error :request/id "request-123"
           :error {:message "network unavailable"}}
          done)))

(deftest fetch-subscriber-submits-error-for-synchronous-fetch-throw
  (async done
         (check-outcome!
          (fn [_ _] (throw (js/Error. "invalid request")))
          {:type :task-form/submit-error :request/id "request-123"
           :error {:message "invalid request"}}
          done)))

(deftest fetch-subscriber-submits-error-for-json-rejection
  (async done
         (check-outcome!
          (fn [_ _]
            (js/Promise.resolve
             #js {:json (fn [] (js/Promise.reject (js/Error. "invalid JSON")))}))
          {:type :task-form/submit-error :request/id "request-123"
           :error {:message "invalid JSON"}}
          done)))

(deftest fetch-handler-uses-supplied-completion-callback
  (async done
         (let [!completions (atom [])
               handler (fetch/make-fetch-handler
                        (fn [_ _] (js/Promise.reject (js/Error. "offline"))))]
           (handler {:event request-event
                     :on-complete! #(swap! !completions conj %)})
           (flush-async!
            (fn []
              (is (= [{:event {:type :task-form/submit-error
                               :request/id "request-123"
                               :error {:message "offline"}}}]
                     @!completions))
              (done))))))

(deftest make-fetch-fn-preserves-legacy-pending-and-outcome-contract
  (async done
         (let [!events (atom [])
               subscriber (fetch/make-fetch-fn
                           #(swap! !events conj %)
                           (fn [_ _] (js/Promise.reject (js/Error. "offline"))))]
           (subscriber request-event nil)
           (flush-async!
            (fn []
              (is (= [{:type :task-form/submit-pending :request/id "request-123"}
                      {:type :task-form/submit-error :request/id "request-123"
                       :error {:message "offline"}}]
                     @!events))
              (done))))))

(deftest composed-program-selects-pending-before-fetch-outcome
  (async done
         (let [!selected (atom [])
               !calls (atom [])
               subscriber (io/make-subscriber!
                           {:pavlov.web.fetch/request
                            (fetch/make-fetch-handler
                             (fn [& args]
                               (swap! !calls conj args)
                               (js/Promise.reject (js/Error. "offline"))))})
               program (bpe/make-program!
                        [[:fetch-state (fetch/make-fetch-bthread)]
                         [:request (b/on :submit
                                         (fn [_] {:request #{request-event}}))]]
                        {:subscribers {:fetch subscriber
                                       :record (fn [event _]
                                                 (swap! !selected conj (event/type event)))}})]
           (bp/submit-event! program :submit)
      ;; Allow the request's asynchronous completion and its queued outcome to run.
           (flush-async!
            (fn []
              (flush-async!
               (fn []
                 (is (= [["/tasks" {:method "POST"}]] @!calls))
                 (is (= [:submit :pavlov.web.fetch/request
                         :task-form/submit-pending :task-form/submit-error]
                        @!selected))
                 (bp/stop! program)
                 (done))))))))

(deftest blocked-request-does-not-start-fetch
  (async done
         (let [!calls (atom [])
               program (bpe/make-program!
                        [[:fetch-state (fetch/make-fetch-bthread)]
                         [:request (b/on :submit
                                         (fn [_] {:request #{request-event}}))]
                         [:block (b/step (fn [_ _]
                                           [nil {:block #{:pavlov.web.fetch/request}}]))]]
                        {:subscribers
                         {:fetch (io/make-subscriber!
                                  {:pavlov.web.fetch/request
                                   (fetch/make-fetch-handler
                                    (fn [& args] (swap! !calls conj args)))})}})]
           (bp/submit-event! program :submit)
           (flush-async!
            (fn []
              (is (empty? @!calls))
              (bp/stop! program)
              (done))))))
