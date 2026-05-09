(ns tech.thomascothran.pavlov.ai.agent-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [tech.thomascothran.pavlov.ai.agent :as agent]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.defaults]))

(deftest agent-requests-llm-call-for-addressed-invocation
  (testing "an addressed invocation becomes a pure LLM call request with response event types"
    (let [bthread (agent/make-agent-bthread
                   {:id :assistant
                    :system "You are a helpful assistant."})
          init-bid (b/notify! bthread nil)
          invocation {:type [:pavlov.ai.agent/invoke :assistant]
                      :conversation-id :conversation-1
                      :message {:role :user
                                :content "Hello"}}
          bid (b/notify! bthread invocation)
          expected-request {:type [:pavlov.ai.llm/call :assistant]
                            :agent-id :assistant
                            :conversation-id :conversation-1
                            :call-id [:assistant :conversation-1 1]
                            :system "You are a helpful assistant."
                            :messages [{:role :user
                                        :content "Hello"}]
                            :tools []
                            :success-event-type [:pavlov.ai.llm/response-received :assistant]
                            :failure-event-type [:pavlov.ai.llm/response-failed :assistant]}]
      (is (= {:wait-on #{[:pavlov.ai.agent/invoke :assistant]
                         [:pavlov.ai.agent/cancel :assistant]
                         [:pavlov.ai.tool/registered :assistant]
                         [:pavlov.ai.tool/deregistered :assistant]
                         [:pavlov.ai.skill/registered :assistant]
                         [:pavlov.ai.skill/deregistered :assistant]}}
             init-bid))
      (is (= {:wait-on #{[:pavlov.ai.agent/invoke :assistant]
                         [:pavlov.ai.agent/cancel :assistant]
                         [:pavlov.ai.tool/registered :assistant]
                         [:pavlov.ai.tool/deregistered :assistant]
                         [:pavlov.ai.skill/registered :assistant]
                         [:pavlov.ai.skill/deregistered :assistant]
                         [:pavlov.ai.llm/response-received :assistant]
                         [:pavlov.ai.llm/response-failed :assistant]}
              :request #{expected-request}}
             bid))
      (is (= :awaiting-llm (:phase (b/state bthread)))))))

(deftest skill-config-requires-id-and-description
  (testing "initial skills and registered skills must declare a skill id and description"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
         #":initial-skills.*:description"
         (agent/make-agent-bthread
          {:id :assistant
           :system "You are a helpful assistant."
           :initial-skills [{:skill-id :debug-clojure}]})))
    (let [bthread (agent/make-agent-bthread
                   {:id :assistant
                    :system "You are a helpful assistant."})
          _ (b/notify! bthread nil)
          bid (b/notify! bthread {:type [:pavlov.ai.skill/registered :assistant]
                                  :skill-id :debug-clojure})]
      (is (= {:wait-on #{[:pavlov.ai.agent/invoke :assistant]
                         [:pavlov.ai.agent/cancel :assistant]
                         [:pavlov.ai.tool/registered :assistant]
                         [:pavlov.ai.tool/deregistered :assistant]
                         [:pavlov.ai.skill/registered :assistant]
                         [:pavlov.ai.skill/deregistered :assistant]}}
             bid))
      (is (empty? (:skills (b/state bthread)))))))

(deftest tool-config-requires-input-and-output-schemas
  (testing "agent tools must declare structured input and output contracts"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
         #":tool.input-schema"
         (agent/make-agent-bthread
          {:id :code-agent
           :system "You are a coding agent."
           :tool {:name :delegate/code
                  :description "Delegate coding tasks to the code agent."
                  :output-schema {:type "object"}}})))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
         #":tool.output-schema"
         (agent/make-agent-bthread
          {:id :code-agent
           :system "You are a coding agent."
           :tool {:name :delegate/code
                  :description "Delegate coding tasks to the code agent."
                  :input-schema {:type "object"}}})))))

(deftest tool-invocation-requests-llm-call-with-tool-contract
  (testing "an exposed agent tool accepts structured arguments and requests an LLM call with its output schema"
    (let [input-schema {:type "object"
                        :properties {:task {:type "string"}}
                        :required ["task"]}
          output-schema {:type "object"
                         :properties {:status {:type "string"}
                                      :summary {:type "string"}}
                         :required ["status" "summary"]}
          bthread (agent/make-agent-bthread
                   {:id :code-agent
                    :system "You are a coding agent."
                    :tool {:name :delegate/code
                           :description "Delegate coding tasks to the code agent."
                           :input-schema input-schema
                           :output-schema output-schema}})
          init-bid (b/notify! bthread nil)
          invocation {:type [:pavlov.ai.tool/invocation :delegate/code]
                      :conversation-id :conversation-1
                      :tool-call-id "call_123"
                      :tool-name :delegate/code
                      :caller-agent-id :planner
                      :arguments {:task "Fix the failing tests"}
                      :success-event-type [:pavlov.ai.tool/succeeded :planner]
                      :failure-event-type [:pavlov.ai.tool/failed :planner]}
          bid (b/notify! bthread invocation)
          expected-request {:type [:pavlov.ai.llm/call :code-agent]
                            :agent-id :code-agent
                            :conversation-id :conversation-1
                            :call-id [:code-agent :conversation-1 1]
                            :system "You are a coding agent."
                            :messages [{:role :user
                                        :content "{:task \"Fix the failing tests\"}"}]
                            :tools []
                            :output-schema output-schema
                            :success-event-type [:pavlov.ai.llm/response-received :code-agent]
                            :failure-event-type [:pavlov.ai.llm/response-failed :code-agent]}]
      (is (= {:wait-on #{[:pavlov.ai.agent/invoke :code-agent]
                         [:pavlov.ai.agent/cancel :code-agent]
                         [:pavlov.ai.tool/registered :code-agent]
                         [:pavlov.ai.tool/deregistered :code-agent]
                         [:pavlov.ai.skill/registered :code-agent]
                         [:pavlov.ai.skill/deregistered :code-agent]
                         [:pavlov.ai.tool/invocation :delegate/code]}}
             init-bid))
      (is (= {:wait-on #{[:pavlov.ai.agent/invoke :code-agent]
                         [:pavlov.ai.agent/cancel :code-agent]
                         [:pavlov.ai.tool/registered :code-agent]
                         [:pavlov.ai.tool/deregistered :code-agent]
                         [:pavlov.ai.skill/registered :code-agent]
                         [:pavlov.ai.skill/deregistered :code-agent]
                         [:pavlov.ai.tool/invocation :delegate/code]
                         [:pavlov.ai.llm/response-received :code-agent]
                         [:pavlov.ai.llm/response-failed :code-agent]}
              :request #{expected-request}}
             bid))
      (is (= :tool (get-in (b/state bthread) [:pending-llm :origin]))))))

(deftest tool-invocation-success-emits-tool-result
  (testing "a successful delegated agent response is routed back as the caller's tool result"
    (let [output-schema {:type "object"
                         :properties {:status {:type "string"}
                                      :summary {:type "string"}}
                         :required ["status" "summary"]}
          bthread (agent/make-agent-bthread
                   {:id :code-agent
                    :system "You are a coding agent."
                    :tool {:name :delegate/code
                           :description "Delegate coding tasks to the code agent."
                           :input-schema {:type "object"}
                           :output-schema output-schema}})
          _ (b/notify! bthread nil)
          _ (b/notify! bthread {:type [:pavlov.ai.tool/invocation :delegate/code]
                                :conversation-id :conversation-1
                                :tool-call-id "call_123"
                                :tool-name :delegate/code
                                :caller-agent-id :planner
                                :arguments {:task "Fix the failing tests"}
                                :success-event-type [:pavlov.ai.tool/succeeded :planner]
                                :failure-event-type [:pavlov.ai.tool/failed :planner]})
          output {:status "completed"
                  :summary "Fixed the failing tests"}
          bid (b/notify! bthread {:type [:pavlov.ai.llm/response-received :code-agent]
                                  :conversation-id :conversation-1
                                  :call-id [:code-agent :conversation-1 1]
                                  :response {:output output
                                             :message {:role :assistant
                                                       :content "Fixed the failing tests"}}})
          expected-result {:type [:pavlov.ai.tool/succeeded :planner]
                           :agent-id :code-agent
                           :conversation-id :conversation-1
                           :call-id [:code-agent :conversation-1 1]
                           :tool-name :delegate/code
                           :tool-call-id "call_123"
                           :result output}]
      (is (= {:wait-on #{[:pavlov.ai.agent/invoke :code-agent]
                         [:pavlov.ai.agent/cancel :code-agent]
                         [:pavlov.ai.tool/registered :code-agent]
                         [:pavlov.ai.tool/deregistered :code-agent]
                         [:pavlov.ai.skill/registered :code-agent]
                         [:pavlov.ai.skill/deregistered :code-agent]
                         [:pavlov.ai.tool/invocation :delegate/code]}
              :request #{expected-result}}
             bid))
      (is (= :idle (:phase (b/state bthread)))))))

(deftest llm-tool-call-requests-registered-tool-invocation
  (testing "an LLM response with tool calls requests the matching registered tool and waits for its result"
    (let [bthread (agent/make-agent-bthread
                   {:id :assistant
                    :system "You are a helpful assistant."
                    :initial-tools [{:tool-name :search
                                     :description "Search documents"
                                     :input-schema {:type "object"
                                                    :properties {:query {:type "string"}}
                                                    :required ["query"]}
                                     :output-schema {:type "object"
                                                     :properties {:results {:type "array"}}
                                                     :required ["results"]}
                                     :invocation-event-type [:pavlov.ai.tool/invocation :search]}]})
          _ (b/notify! bthread nil)
          _ (b/notify! bthread {:type [:pavlov.ai.agent/invoke :assistant]
                                :conversation-id :conversation-1
                                :message {:role :user
                                          :content "Find docs about Pavlov."}})
          assistant-message {:role :assistant
                             :content nil
                             :tool-calls [{:id "call_search_1"
                                           :name :search
                                           :arguments {:query "Pavlov"}}]}
          bid (b/notify! bthread {:type [:pavlov.ai.llm/response-received :assistant]
                                  :conversation-id :conversation-1
                                  :call-id [:assistant :conversation-1 1]
                                  :response {:message assistant-message
                                             :finish-reason :tool-calls}})
          expected-tool-request {:type [:pavlov.ai.tool/invocation :search]
                                 :agent-id :assistant
                                 :conversation-id :conversation-1
                                 :tool-name :search
                                 :tool-call-id "call_search_1"
                                 :arguments {:query "Pavlov"}
                                 :success-event-type [:pavlov.ai.tool/succeeded :assistant]
                                 :failure-event-type [:pavlov.ai.tool/failed :assistant]}]
      (is (= {:wait-on #{[:pavlov.ai.agent/invoke :assistant]
                         [:pavlov.ai.agent/cancel :assistant]
                         [:pavlov.ai.tool/registered :assistant]
                         [:pavlov.ai.tool/deregistered :assistant]
                         [:pavlov.ai.skill/registered :assistant]
                         [:pavlov.ai.skill/deregistered :assistant]
                         [:pavlov.ai.tool/succeeded :assistant]
                         [:pavlov.ai.tool/failed :assistant]}
              :request #{expected-tool-request}}
             bid))
      (is (= :awaiting-tools (:phase (b/state bthread)))))))

(deftest tool-result-requests-next-llm-call
  (testing "when all requested tools return, the agent appends tool results and asks the LLM to continue"
    (let [bthread (agent/make-agent-bthread
                   {:id :assistant
                    :system "You are a helpful assistant."
                    :initial-tools [{:tool-name :search
                                     :description "Search documents"
                                     :input-schema {:type "object"}
                                     :output-schema {:type "object"}
                                     :invocation-event-type [:pavlov.ai.tool/invocation :search]}]})
          _ (b/notify! bthread nil)
          _ (b/notify! bthread {:type [:pavlov.ai.agent/invoke :assistant]
                                :conversation-id :conversation-1
                                :message {:role :user
                                          :content "Find docs about Pavlov."}})
          assistant-message {:role :assistant
                             :content nil
                             :tool-calls [{:id "call_search_1"
                                           :name :search
                                           :arguments {:query "Pavlov"}}]}
          _ (b/notify! bthread {:type [:pavlov.ai.llm/response-received :assistant]
                                :conversation-id :conversation-1
                                :call-id [:assistant :conversation-1 1]
                                :response {:message assistant-message
                                           :finish-reason :tool-calls}})
          tool-result {:results ["Pavlov is a behavioral programming library."]}
          bid (b/notify! bthread {:type [:pavlov.ai.tool/succeeded :assistant]
                                  :conversation-id :conversation-1
                                  :tool-name :search
                                  :tool-call-id "call_search_1"
                                  :result tool-result})
          expected-request {:type [:pavlov.ai.llm/call :assistant]
                            :agent-id :assistant
                            :conversation-id :conversation-1
                            :call-id [:assistant :conversation-1 2]
                            :system "You are a helpful assistant."
                            :messages [{:role :user
                                        :content "Find docs about Pavlov."}
                                       assistant-message
                                       {:role :tool
                                        :tool-call-id "call_search_1"
                                        :name :search
                                        :content (pr-str tool-result)}]
                            :tools [{:tool-name :search
                                     :description "Search documents"
                                     :input-schema {:type "object"}
                                     :output-schema {:type "object"}
                                     :invocation-event-type [:pavlov.ai.tool/invocation :search]}]
                            :success-event-type [:pavlov.ai.llm/response-received :assistant]
                            :failure-event-type [:pavlov.ai.llm/response-failed :assistant]}]
      (is (= {:wait-on #{[:pavlov.ai.agent/invoke :assistant]
                         [:pavlov.ai.agent/cancel :assistant]
                         [:pavlov.ai.tool/registered :assistant]
                         [:pavlov.ai.tool/deregistered :assistant]
                         [:pavlov.ai.skill/registered :assistant]
                         [:pavlov.ai.skill/deregistered :assistant]
                         [:pavlov.ai.llm/response-received :assistant]
                         [:pavlov.ai.llm/response-failed :assistant]}
              :request #{expected-request}}
             bid))
      (is (= :awaiting-llm (:phase (b/state bthread)))))))
