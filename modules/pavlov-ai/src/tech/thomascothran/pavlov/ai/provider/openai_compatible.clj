(ns tech.thomascothran.pavlov.ai.provider.openai-compatible
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [tech.thomascothran.pavlov.ai.provider :as provider]))

(defn- authorization-headers
  [api-key]
  (when api-key
    {"Authorization" (str "Bearer " api-key)}))

(defn- request-options
  [{:keys [api-key headers body]}]
  {:headers (merge {"Content-Type" "application/json"}
                   (authorization-headers api-key)
                   headers)
   :body (json/write-str (assoc body :stream false))
   :throw-exceptions false})

(defn- decode-response-body
  [body]
  (if (and (string? body) (seq body))
    (json/read-str body :key-fn keyword)
    body))

(defmethod provider/chat-completion! :openai-compatible
  [_provider {:keys [post! url body] :as options}]
  {:pre [(fn? post!)
         (string? url)
         (map? body)]}
  (-> (post! url (request-options options))
      (update :body decode-response-body)))

(defn- successful-status?
  [status]
  (and (integer? status)
       (<= 200 status 299)))

(defn- failure
  [category kind message details]
  {:outcome :failure
   :cognitect.anomalies/category category
   :cognitect.anomalies/message message
   :error (merge {:kind kind
                  :message message}
                 details)})

(defn- malformed
  ([message]
   (malformed message nil))
  ([message details]
   (failure :cognitect.anomalies/fault
            :malformed-response
            message
            details)))

(defn- http-status-category
  [status]
  (cond
    (#{401 403} status) :cognitect.anomalies/forbidden
    (= 404 status) :cognitect.anomalies/not-found
    (= 408 status) :cognitect.anomalies/interrupted
    (= 409 status) :cognitect.anomalies/conflict
    (= 429 status) :cognitect.anomalies/busy
    (<= 500 status 599) :cognitect.anomalies/unavailable
    (<= 400 status 499) :cognitect.anomalies/incorrect
    :else :cognitect.anomalies/fault))

(defn- provider-error
  [category kind status body]
  (let [error (when (map? body) (:error body))
        message (or (:message error)
                    (str "Provider returned HTTP status " status))]
    (failure category
             kind
             message
             (cond-> {:status status}
               (:code error) (assoc :provider-code (:code error))
               (:type error) (assoc :provider-type (:type error))))))

(defn- decode-action-arguments
  [provider-call-id arguments]
  (let [decoded
        (cond
          (map? arguments)
          arguments

          (string? arguments)
          (try
            (json/read-str arguments :key-fn keyword)
            (catch Exception _error
              (malformed
               "OpenAI-compatible action arguments are not valid JSON"
               {:provider-call-id provider-call-id})))

          :else
          (malformed
           "OpenAI-compatible action arguments must be a JSON object"
           {:provider-call-id provider-call-id}))]
    (cond
      (provider/anomaly? decoded)
      decoded

      (map? decoded)
      decoded

      :else
      (malformed
       "OpenAI-compatible action arguments must be a JSON object"
       {:provider-call-id provider-call-id}))))

(defn- normalize-action-call
  [{:keys [id type function]}]
  (cond
    (not (and (string? id) (seq id)))
    (malformed "OpenAI-compatible action call is missing an id")

    (not= "function" type)
    (malformed "OpenAI-compatible action call is not a function"
               {:provider-call-id id})

    (not (and (string? (:name function))
              (seq (:name function))))
    (malformed "OpenAI-compatible action call is missing a function name"
               {:provider-call-id id})

    :else
    (-> (decode-action-arguments id (:arguments function))
        ((provider/bail-on-anomaly
          (fn [arguments]
            {:provider-call-id id
             :name (:name function)
             :arguments arguments}))))))

(defn- normalize-action-calls
  [tool-calls]
  (cond
    (nil? tool-calls)
    []

    (sequential? tool-calls)
    (reduce (fn [action-calls tool-call]
              (let [action-call (normalize-action-call tool-call)]
                (if (provider/anomaly? action-call)
                  (reduced action-call)
                  (conj action-calls action-call))))
            []
            tool-calls)

    :else
    (malformed "OpenAI-compatible message has invalid tool_calls")))

(defn- normalize-finish-reason
  [finish-reason]
  (cond
    (nil? finish-reason)
    nil

    (or (string? finish-reason) (keyword? finish-reason))
    (let [normalized (-> finish-reason
                         name
                         (str/replace "_" "-")
                         keyword)]
      (if (= :tool-calls normalized)
        :action-calls
        normalized))

    :else
    (malformed "OpenAI-compatible response has an invalid finish reason")))

(defn- normalize-usage
  [usage]
  (cond
    (nil? usage)
    nil

    (map? usage)
    (cond-> {}
      (contains? usage :prompt_tokens)
      (assoc :input-tokens (:prompt_tokens usage))

      (contains? usage :completion_tokens)
      (assoc :output-tokens (:completion_tokens usage))

      (contains? usage :total_tokens)
      (assoc :total-tokens (:total_tokens usage)))

    :else
    (malformed "OpenAI-compatible response has invalid usage data")))

(defn- provider-metadata
  [body]
  (cond-> {}
    (contains? body :id)
    (assoc :response-id (:id body))

    (contains? body :model)
    (assoc :model (:model body))))

(defn- completion-state
  [body]
  (if-not (map? body)
    (malformed "OpenAI-compatible response body is not an object")
    (let [choice (first (:choices body))
          provider-message (:message choice)]
      (if-not (map? provider-message)
        (malformed
         "OpenAI-compatible response is missing choices[0].message")
        {:body body
         :choice choice
         :provider-message provider-message}))))

(defn- add-message
  [{:keys [provider-message] :as state}]
  (let [content (:content provider-message)]
    (cond
      (not= "assistant" (:role provider-message))
      (malformed
       "OpenAI-compatible response message is not from the assistant")

      (not (or (nil? content) (string? content)))
      (malformed "OpenAI-compatible response content is not text")

      :else
      (assoc state :message {:role "assistant"
                             :content content}))))

(defn- add-action-calls
  [{:keys [provider-message] :as state}]
  (let [action-calls (normalize-action-calls (:tool_calls provider-message))]
    (if (provider/anomaly? action-calls)
      action-calls
      (assoc state :action-calls action-calls))))

(defn- require-completion-content
  [{:keys [message action-calls] :as state}]
  (if (or (seq action-calls)
          (seq (:content message)))
    state
    (malformed
     "OpenAI-compatible response contains neither content nor actions")))

(defn- add-finish-reason
  [{:keys [choice] :as state}]
  (let [finish-reason (normalize-finish-reason (:finish_reason choice))]
    (if (provider/anomaly? finish-reason)
      finish-reason
      (assoc state :finish-reason finish-reason))))

(defn- add-usage
  [{:keys [body] :as state}]
  (let [usage (normalize-usage (:usage body))]
    (if (provider/anomaly? usage)
      usage
      (assoc state :usage usage))))

(defn- completion-success
  [{:keys [body message action-calls finish-reason usage]}]
  {:outcome :success
   :message message
   :action-calls action-calls
   :finish-reason finish-reason
   :usage usage
   :provider-metadata (provider-metadata body)})

(defn- normalize-success
  [body]
  (-> body
      completion-state
      ((provider/bail-on-anomaly add-message))
      ((provider/bail-on-anomaly add-action-calls))
      ((provider/bail-on-anomaly require-completion-content))
      ((provider/bail-on-anomaly add-finish-reason))
      ((provider/bail-on-anomaly add-usage))
      ((provider/bail-on-anomaly completion-success))))

(defmethod provider/normalize-chat-completion :openai-compatible
  [_provider {:keys [status body] :as response}]
  {:pre [(map? response)]}
  (cond
    (not (integer? status))
    (failure :cognitect.anomalies/fault
             :malformed-http-response
             "OpenAI-compatible HTTP response is missing an integer status"
             {})

    (not (successful-status? status))
    (provider-error (http-status-category status)
                    :provider-http-error
                    status
                    body)

    (and (map? body) (:error body))
    (provider-error :cognitect.anomalies/fault
                    :provider-error
                    status
                    body)

    :else
    (normalize-success body)))

(comment
  (require '[hato.client :as http])

  (def openrouter-api-key
    (delay (read-line)))

  (def r
    (provider/chat-completion!
     :openai-compatible
     {:post! http/post
      :url "https://openrouter.ai/api/v1/chat/completions"
      :api-key @openrouter-api-key
      :body {:model "deepseek/deepseek-v4-flash"
             :messages [{:role "user"
                         :content "Say hello in one short sentence."}]}}))
  (provider/normalize-chat-completion :openai-compatible r))
