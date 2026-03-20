(ns tech.thomascothran.pavlov.web.fetch
  (:require [tech.thomascothran.pavlov.bthread :as b]))

#?(:cljs
   (defn- resolved-response-event
     [{:keys [request/id response-event-type]} response body]
     {:type response-event-type
      :request/id id
      :status (.-status response)
      :ok (.-ok response)
      :headers (js->clj (.-headers response))
      :body (js->clj body :keywordize-keys true)}))

#?(:cljs
   (defn- rejected-fetch-event
     [{:keys [request/id error-event-type]} error]
     {:type error-event-type
      :request/id id
      :error {:message (.-message error)}}))

(defn- kickoff-fetch!
  [fetch! {:keys [request/id url fetch-opts in-flight-event-type]}]
  {:pending-event {:type in-flight-event-type
                   :request/id id}
   :request-promise (fetch! url fetch-opts)})

#?(:cljs
   (defn- submit-fetch-follow-up!
     [submit-event! event request-promise]
     (-> request-promise
         (.then (fn [response]
                  (-> ((.-json response))
                      (.then (fn [body]
                               (submit-event!
                                (resolved-response-event event response body)))))))
         (.catch (fn [error]
                   (submit-event! (rejected-fetch-event event error)))))))

(defn make-fetch-fn
  [submit-event! fetch!]
  (fn [event _]
    (let [{:keys [pending-event request-promise]}
          (kickoff-fetch! fetch! event)]
      (submit-event! pending-event)
      #?(:cljs
         (submit-fetch-follow-up! submit-event! event request-promise)))))

(defn make-fetch-bthread
  [submit-event! fetch!]
  (b/on :pavlov.web.fetch/request
        (fn [event]
          (let [{:keys [pending-event request-promise]}
                (kickoff-fetch! fetch! event)]
            #?(:cljs
               (submit-fetch-follow-up! submit-event! event request-promise))
            {:request #{pending-event}}))))
