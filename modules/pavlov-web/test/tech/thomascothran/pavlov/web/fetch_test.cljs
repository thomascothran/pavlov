(ns tech.thomascothran.pavlov.web.fetch-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [tech.thomascothran.pavlov.bprogram.proto :as bp]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.web.fetch :as fetch]))

(defn make-fake-bprogram
  [!subscriber !submitted-events]
  (reify bp/BProgram
    (stop! [_]
      (js/Promise.resolve nil))
    (stopped [_]
      false)
    (kill! [_]
      nil)
    (submit-event! [_ event]
      (swap! !submitted-events conj event))
    (subscribe! [_ _ subscriber]
      (reset! !subscriber subscriber))))

(defn fake-response
  [{:keys [status ok headers body-json]}]
  #js {:status status
       :ok ok
       :headers (clj->js headers)
       :json (fn []
               (js/Promise.resolve (js/JSON.parse body-json)))})

(defn flush-async!
  [f]
  (js/setTimeout f 0))

(deftest fetch-namespace-loads
  (is true))

(deftest make-fetch-bthread-requests-in-flight-event-and-starts-fetch-on-request
  (let [!submitted-events (atom [])
        !fetch-calls (atom [])
        fetch! (fn [& args]
                 (swap! !fetch-calls conj args)
                 (js/Promise. (fn [_ _] nil)))
        request-id "request-123"
        request-event {:type :pavlov.web.fetch/request
                       :request/id request-id
                       :url "/tasks"
                       :fetch-opts {:method "POST"}
                       :decode :json
                       :in-flight-event-type :task-form/submit-pending
                       :response-event-type :task-form/submit-response
                       :error-event-type :task-form/submit-error}
        bthread (fetch/make-fetch-bthread #(swap! !submitted-events conj %) fetch!)
        init-bid (b/notify! bthread nil)
        bid (b/notify! bthread request-event)]
    (is (= {:wait-on #{:pavlov.web.fetch/request}}
           init-bid))
    (is (= [["/tasks" {:method "POST"}]]
           @!fetch-calls))
    (is (= {:wait-on #{:pavlov.web.fetch/request}
            :request #{{:type :task-form/submit-pending
                        :request/id request-id}}}
           bid))
    (is (= [] @!submitted-events))))

(deftest make-fetch-bthread-submits-response-event-for-2xx-responses
  (async done
         (let [!submitted-events (atom [])
               fetch! (fn [_ _]
                        (js/Promise.resolve
                         (fake-response
                          {:status 201
                           :ok true
                           :headers {"content-type" "application/json"}
                           :body-json "{\"task/id\":123,\"task-name\":\"Take out trash\"}"})))
               request-id "request-2xx"
               request-event {:type :pavlov.web.fetch/request
                              :request/id request-id
                              :url "/tasks"
                              :fetch-opts {:method "POST"}
                              :decode :json
                              :in-flight-event-type :task-form/submit-pending
                              :response-event-type :task-form/submit-response
                              :error-event-type :task-form/submit-error}
               bthread (fetch/make-fetch-bthread #(swap! !submitted-events conj %) fetch!)
               init-bid (b/notify! bthread nil)
               bid (b/notify! bthread request-event)]
           (is (= {:wait-on #{:pavlov.web.fetch/request}}
                  init-bid))
           (is (= {:wait-on #{:pavlov.web.fetch/request}
                   :request #{{:type :task-form/submit-pending
                               :request/id request-id}}}
                  bid))
           (flush-async!
            (fn []
              (is (= [{:type :task-form/submit-response
                       :request/id request-id
                       :status 201
                       :ok true
                       :headers {"content-type" "application/json"}
                       :body {:task/id 123
                              :task-name "Take out trash"}}]
                     @!submitted-events))
              (done))))))

(deftest make-fetch-bthread-submits-response-event-for-4xx-responses
  (async done
         (let [!submitted-events (atom [])
               fetch! (fn [_ _]
                        (js/Promise.resolve
                         (fake-response
                          {:status 422
                           :ok false
                           :headers {"content-type" "application/json"}
                           :body-json "{\"errors\":{\"task-name\":[\"Must be at least 3 characters\"]}}"})))
               request-id "request-4xx"
               request-event {:type :pavlov.web.fetch/request
                              :request/id request-id
                              :url "/tasks"
                              :fetch-opts {:method "POST"}
                              :decode :json
                              :in-flight-event-type :task-form/submit-pending
                              :response-event-type :task-form/submit-response
                              :error-event-type :task-form/submit-error}
               bthread (fetch/make-fetch-bthread #(swap! !submitted-events conj %) fetch!)
               init-bid (b/notify! bthread nil)
               bid (b/notify! bthread request-event)]
           (is (= {:wait-on #{:pavlov.web.fetch/request}}
                  init-bid))
           (is (= {:wait-on #{:pavlov.web.fetch/request}
                   :request #{{:type :task-form/submit-pending
                               :request/id request-id}}}
                  bid))
           (flush-async!
            (fn []
              (is (= [{:type :task-form/submit-response
                       :request/id request-id
                       :status 422
                       :ok false
                       :headers {"content-type" "application/json"}
                       :body {:errors {:task-name ["Must be at least 3 characters"]}}}]
                     @!submitted-events))
              (done))))))

(deftest make-fetch-bthread-submits-error-event-for-rejected-fetch-before-response
  (async done
         (let [!submitted-events (atom [])
               fetch-error (js/Error. "network unavailable")
               fetch! (fn [_ _]
                        (doto (js/Promise.reject fetch-error)
                          (.catch (fn [_] nil))))
               request-id "request-rejected"
               request-event {:type :pavlov.web.fetch/request
                              :request/id request-id
                              :url "/tasks"
                              :fetch-opts {:method "POST"}
                              :decode :json
                              :in-flight-event-type :task-form/submit-pending
                              :response-event-type :task-form/submit-response
                              :error-event-type :task-form/submit-error}
               bthread (fetch/make-fetch-bthread #(swap! !submitted-events conj %) fetch!)
               init-bid (b/notify! bthread nil)
               bid (b/notify! bthread request-event)]
           (.once js/process "unhandledRejection" (fn [_ _] nil))
           (is (= {:wait-on #{:pavlov.web.fetch/request}}
                  init-bid))
           (is (= {:wait-on #{:pavlov.web.fetch/request}
                   :request #{{:type :task-form/submit-pending
                               :request/id request-id}}}
                  bid))
           (flush-async!
            (fn []
              (is (= [{:type :task-form/submit-error
                       :request/id request-id
                       :error {:message "network unavailable"}}]
                     @!submitted-events))
              (done))))))

(deftest make-fetch-fn-emits-error-event-for-rejected-fetch-before-response
  (async done
         (let [!submitted-events (atom [])
               fetch-error (js/Error. "network unavailable")
               fetch! (fn [_ _]
                        (doto (js/Promise.reject fetch-error)
                          (.catch (fn [_] nil))))
               request-id "request-rejected"
               request-event {:type :pavlov.web.fetch/request
                              :request/id request-id
                              :url "/tasks"
                              :fetch-opts {:method "POST"}
                              :decode :json
                              :in-flight-event-type :task-form/submit-pending
                              :response-event-type :task-form/submit-response
                              :error-event-type :task-form/submit-error}
               subscriber (fetch/make-fetch-fn #(swap! !submitted-events conj %) fetch!)]
           (.once js/process "unhandledRejection" (fn [_ _] nil))
           (subscriber request-event nil)
           (flush-async!
            (fn []
              (is (= [{:type :task-form/submit-pending
                       :request/id request-id}
                      {:type :task-form/submit-error
                       :request/id request-id
                       :error {:message "network unavailable"}}]
                     @!submitted-events))
              (done))))))
