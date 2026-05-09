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
