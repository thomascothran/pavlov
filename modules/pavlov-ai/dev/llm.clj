(ns llm
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hato.client :as http]))

;; Fill this in locally. Keeping it behind a delay avoids realizing the secret
;; until a request is actually made.
(def openrouter-api-key
  (delay (-> (slurp "/home/default/.secrets/pavlov-ai-openrouter-token")
             str/trim)))

(comment
  (deref openrouter-api-key))

(def openrouter-chat-completions-url
  "https://openrouter.ai/api/v1/chat/completions")

(def default-model
  "deepseek/deepseek-v4-flash")

(def default-messages
  [{:role "user"
    :content "Say hello in one short sentence."}])

(defn- json-request [body]
  (json/write-str body))

(defn- json-response [body]
  (when (seq body)
    (json/read-str body :key-fn keyword)))

(defn- auth-header []
  (str "Bearer " @openrouter-api-key))

(defn- request-options [body]
  {:headers {"Authorization" (auth-header)
             "Content-Type" "application/json"}
   :body (json-request body)
   :throw-exceptions false})

(def sort-by-tool
  {:type "function"
   :function
   {:name "sort-by"
    :description "Sort a JSON array of objects by one object key using Clojure's sort-by."
    :parameters
    {:type "object"
     :additionalProperties false
     :properties
     {:items {:type "array"
              :description "Objects to sort."
              :items {:type "object"}}
      :key {:type "string"
            :description "Object key to sort by, for example: name, age, score."}
      :descending {:type "boolean"
                   :description "Sort descending when true. Defaults to false."}}
     :required ["items" "key"]}}})

(defn run-sort-by-tool
  "Implementation for the `sort-by` tool."
  [{:keys [items key descending]}]
  (let [k (keyword key)
        sorted-items (sort-by #(get % k) items)]
    (vec (if descending
           (reverse sorted-items)
           sorted-items))))

(def tool-handlers
  {"sort-by" run-sort-by-tool})

(defn- decode-tool-arguments [tool-call]
  (json/read-str (get-in tool-call [:function :arguments]) :key-fn keyword))

(defn- tool-response-message [tool-call]
  (let [tool-name (get-in tool-call [:function :name])
        handler (get tool-handlers tool-name)
        result (if handler
                 (handler (decode-tool-arguments tool-call))
                 {:error (str "No tool handler for " tool-name)})]
    {:role "tool"
     :tool_call_id (:id tool-call)
     :name tool-name
     :content (json/write-str result)}))

(defn chat-completion
  "Call OpenRouter's /api/v1/chat/completions endpoint.

  Examples:

    (chat-completion \"What is Pavlov?\")

    (chat-completion {:model \"openai/gpt-4o-mini\"
                      :messages [{:role \"system\" :content \"Be concise.\"}
                                 {:role \"user\" :content \"What is Pavlov?\"}]
                      :temperature 0.2})

  Returns a map with the decoded request body and decoded response body so it is
  easy to inspect both sides of the API call. The Authorization header is not
  included in the returned request map."
  ([]
   (chat-completion {:messages default-messages}))
  ([prompt-or-request]
   (let [body (if (string? prompt-or-request)
                {:messages [{:role "user" :content prompt-or-request}]}
                prompt-or-request)
         body (merge {:model default-model}
                     body)
         response (http/post openrouter-chat-completions-url
                             (request-options body))]
     {:request {:url openrouter-chat-completions-url
                :method :post
                :body body}
      :response (update response :body json-response)})))

(defn sort-by-tool-example
  "Ask the model to call the `sort-by` tool, execute that tool locally with
  Clojure, then send the tool result back for a final answer.

  Returns the first LLM response, local tool messages, and final LLM response so
  the full request/response flow can be inspected."
  []
  (let [messages [{:role "user"
                   :content (str "Sort these people by age ascending using the sort-by tool, "
                                 "then summarize the sorted order: "
                                 "[{\"name\":\"Ada\",\"age\":36},"
                                 "{\"name\":\"Grace\",\"age\":30},"
                                 "{\"name\":\"Edsger\",\"age\":42}]")}]
        first-response (chat-completion {:messages messages
                                         :tools [sort-by-tool]
                                         :tool_choice {:type "function"
                                                       :function {:name "sort-by"}}})
        _ (def first-response first-response)
        assistant-message (get-in first-response [:response :body :choices 0 :message])
        tool-calls (:tool_calls assistant-message)
        tool-messages (mapv tool-response-message tool-calls)
        final-response (chat-completion {:messages (into (conj messages assistant-message)
                                                         tool-messages)})]
    {:first-response first-response
     :tool-messages tool-messages
     :final-response final-response}))

(comment
  (chat-completion "Hi there. Explain Dedekind cuts in a haiku")

  (sort-by-tool-example))
