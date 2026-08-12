(ns tech.thomascothran.pavlov.ai.provider.openai-compatible-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.ai.provider :as provider]
            ;; Requiring this namespace installs the provider method.
            [tech.thomascothran.pavlov.ai.provider.openai-compatible]))

(deftest chat-completion-uses-injected-post
  (testing "an OpenAI-compatible request is posted and its JSON body is decoded"
    (let [calls (atom [])
          post! (fn [url options]
                  (swap! calls conj
                         {:url url
                          :options (update options :body
                                           #(json/read-str % :key-fn keyword))})
                  {:status 200
                   :headers {"content-type" "application/json"}
                   :body (json/write-str
                          {:id "completion-1"
                           :choices [{:message {:role "assistant"
                                               :content "Hello"}
                                      :finish_reason "stop"}]})})
          response
          (provider/chat-completion!
           :openai-compatible
           {:post! post!
            :url "https://openrouter.ai/api/v1/chat/completions"
            :api-key "test-api-key"
            :headers {"X-Title" "Pavlov"}
            :body {:model "openai/gpt-4o-mini"
                   :messages [{:role "user"
                               :content "Say hello"}]}})]
      (is (= [{:url "https://openrouter.ai/api/v1/chat/completions"
               :options
               {:headers {"Authorization" "Bearer test-api-key"
                          "Content-Type" "application/json"
                          "X-Title" "Pavlov"}
                :body {:model "openai/gpt-4o-mini"
                       :messages [{:role "user"
                                   :content "Say hello"}]
                       :stream false}
                :throw-exceptions false}}]
             @calls))
      (is (= {:status 200
              :headers {"content-type" "application/json"}
              :body {:id "completion-1"
                     :choices [{:message {:role "assistant"
                                          :content "Hello"}
                                :finish_reason "stop"}]}}
             response)))))

(deftest bail-on-anomaly-short-circuits-a-transformation
  (let [calls (atom 0)
        transform (provider/bail-on-anomaly
                   (fn [x]
                     (swap! calls inc)
                     (assoc x :transformed true)))
        anomaly {:cognitect.anomalies/category :cognitect.anomalies/fault}]
    (is (= anomaly (transform anomaly)))
    (is (= {:value 1 :transformed true}
           (transform {:value 1})))
    (is (= 1 @calls))))

(deftest normalize-text-completion
  (testing "an OpenAI-compatible text response is reduced to the canonical shape"
    (is (= {:outcome :success
            :message {:role "assistant"
                      :content "Hello"}
            :action-calls []
            :finish-reason :stop
            :usage {:input-tokens 12
                    :output-tokens 3
                    :total-tokens 15}
            :provider-metadata {:response-id "completion-1"
                                :model "openai/gpt-4o-mini"}}
           (provider/normalize-chat-completion
            :openai-compatible
            {:status 200
             :headers {"content-type" "application/json"}
             :body {:id "completion-1"
                    :model "openai/gpt-4o-mini"
                    :choices [{:message {:role "assistant"
                                         :content "Hello"}
                               :finish_reason "stop"}]
                    :usage {:prompt_tokens 12
                            :completion_tokens 3
                            :total_tokens 15}}})))))

(deftest normalize-action-completion
  (testing "provider tool calls become provider-neutral action calls"
    (is (= {:outcome :success
            :message {:role "assistant"
                      :content nil}
            :action-calls [{:provider-call-id "call-123"
                            :name "email_list"
                            :arguments {:lookback 20}}]
            :finish-reason :action-calls
            :usage nil
            :provider-metadata {:response-id "completion-2"
                                :model "deepseek/deepseek-v4-flash"}}
           (provider/normalize-chat-completion
            :openai-compatible
            {:status 200
             :body {:id "completion-2"
                    :model "deepseek/deepseek-v4-flash"
                    :choices
                    [{:message
                      {:role "assistant"
                       :content nil
                       :tool_calls
                       [{:id "call-123"
                         :type "function"
                         :function {:name "email_list"
                                    :arguments "{\"lookback\":20}"}}]}
                      :finish_reason "tool_calls"}]}})))))

(deftest normalize-provider-http-error
  (testing "provider error bodies become canonical failures"
    (is (= {:outcome :failure
            :cognitect.anomalies/category :cognitect.anomalies/busy
            :cognitect.anomalies/message "Rate limit exceeded"
            :error {:kind :provider-http-error
                    :status 429
                    :message "Rate limit exceeded"
                    :provider-code "rate_limit_exceeded"
                    :provider-type "rate_limit_error"}}
           (provider/normalize-chat-completion
            :openai-compatible
            {:status 429
             :body {:error {:message "Rate limit exceeded"
                            :code "rate_limit_exceeded"
                            :type "rate_limit_error"}}})))))

(deftest normalize-malformed-completion
  (testing "missing choices are reported without leaking the raw response"
    (is (= {:outcome :failure
            :cognitect.anomalies/category :cognitect.anomalies/fault
            :cognitect.anomalies/message
            (str "OpenAI-compatible response is missing "
                 "choices[0].message")
            :error {:kind :malformed-response
                    :message (str "OpenAI-compatible response is missing "
                                  "choices[0].message")}}
           (provider/normalize-chat-completion
            :openai-compatible
            {:status 200
             :body {:id "completion-without-choices"}}))))

  (testing "invalid action argument JSON is a malformed response"
    (is (= {:outcome :failure
            :cognitect.anomalies/category :cognitect.anomalies/fault
            :cognitect.anomalies/message
            (str "OpenAI-compatible action arguments are "
                 "not valid JSON")
            :error {:kind :malformed-response
                    :message (str "OpenAI-compatible action arguments are "
                                  "not valid JSON")
                    :provider-call-id "call-invalid"}}
           (provider/normalize-chat-completion
            :openai-compatible
            {:status 200
             :body {:choices
                    [{:message
                      {:role "assistant"
                       :content nil
                       :tool_calls
                       [{:id "call-invalid"
                         :type "function"
                         :function {:name "email_list"
                                    :arguments "not-json"}}]}
                      :finish_reason "tool_calls"}]}})))))
