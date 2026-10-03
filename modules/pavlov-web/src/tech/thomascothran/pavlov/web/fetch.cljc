(ns tech.thomascothran.pavlov.web.fetch
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.web.dom.interop :as interop]))

#?(:cljs
   (defn- headers->map
     [headers]
     (let [entries (cond
                     (nil? headers) nil
                     (fn? (.-entries headers)) (.entries headers)
                     :else (js/Object.entries headers))]
       (reduce (fn [acc entry]
                 (assoc acc (aget entry 0) (aget entry 1)))
               {}
               (interop/collection-seq entries)))))

#?(:squint
   (defn- json->data
     [value]
     ;; Squint already represents parsed JSON as its native map/vector data.
     value)
   :cljs
   (defn- json->data
     [value]
     (js->clj value :keywordize-keys true)))

#?(:cljs
   (defn- resolved-response-event
     [{:keys [request/id response-event-type]} response body]
     {:type response-event-type
      :request/id id
      :status (.-status response)
      :ok (.-ok response)
      :headers (headers->map (.-headers response))
      :body (json->data body)}))

#?(:cljs
   (defn- rejected-fetch-event
     [{:keys [request/id error-event-type]} error]
     {:type error-event-type
      :request/id id
      :error {:message (.-message error)}}))

(defn- pending-event
  [{:keys [request/id in-flight-event-type]}]
  {:type in-flight-event-type
   :request/id id})

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

(defn make-fetch-handler
  "Create a browser Fetch handler for `io/make-subscriber!`.

   Register under `:pavlov.web.fetch/request`. Receives
   `{:event request-event :on-complete! callback}` and reports a correlated
   response or error through `callback` as `{:event outcome-event}`. HTTP error
   statuses are responses; network, synchronous Fetch, and JSON parsing failures
   are errors. Responses are decoded as JSON.

   Starts asynchronous work and returns promptly. Pending state is requested by
   `make-fetch-bthread`, not submitted by this handler. The injected `fetch!`
   takes URL and native Fetch options and returns a Promise."
  [fetch!]
  (fn [{:keys [event on-complete!]}]
    #?(:cljs
       (let [submit! #(on-complete! {:event %})]
         (try
           (submit-fetch-follow-up! submit! event
                                   (fetch! (:url event) (:fetch-opts event)))
           (catch :default error
             (submit! (rejected-fetch-event event error)))))
       :clj
       (throw (ex-info "Fetch handler is only available in JavaScript" {})))
    nil))

(defn make-fetch-fn
  "Legacy subscriber wrapper using an explicit submit function.

   Submits pending state directly, then delegates IO to `make-fetch-handler`.
   For new programs use an IO subscriber plus `make-fetch-bthread` instead;
   do not install both paths for the same request."
  [submit-event! fetch!]
  (let [handler (make-fetch-handler fetch!)]
    (fn [event _]
      (submit-event! (pending-event event))
      (handler {:event event
                :on-complete! #(submit-event! (:event %))}))))

(defn make-fetch-bthread
  "Request correlated pending state when a Fetch request is selected.

   This bthread performs no IO. Register `make-fetch-handler` with
   `io/make-subscriber!` to execute requests and report outcomes."
  []
  (b/on :pavlov.web.fetch/request
        (fn [event]
          {:request #{(pending-event event)}})))
