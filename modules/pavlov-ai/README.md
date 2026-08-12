# Pavlov AI

Pavlov AI provides bthreads and event conventions for composing LLM-backed agents inside Pavlov behavioral programs.

The main design rule is that an agent bthread should stay pure: it requests LLM/tool work as Pavlov events, and separate runtime bthreads or subscribers perform side effects and answer with configured response event types.

## Addressed event types

Agents should usually use event types that include the agent id instead of relying on shared event types plus ad-hoc filtering.

For an agent with id `:assistant`, default event types are shaped like:

```clojure
[:pavlov.ai.agent/invoke :assistant]
[:pavlov.ai.agent/responded :assistant]
[:pavlov.ai.agent/failed :assistant]
[:pavlov.ai.llm/call :assistant]
[:pavlov.ai.llm/response-received :assistant]
[:pavlov.ai.llm/response-failed :assistant]
[:pavlov.ai.tool/registered :assistant]
[:pavlov.ai.skill/registered :assistant]
```

This keeps Pavlov subscriptions precise: the bthread waits on exactly the event types that can wake it.

## LLM call requests

The agent emits an LLM call request that declares response event **types**, not full response event maps:

```clojure
{:type [:pavlov.ai.llm/call :assistant]
 :agent-id :assistant
 :conversation-id :conversation/main
 :call-id [:assistant :conversation/main 1]
 :system "You are helpful."
 :messages [{:role :user :content "Hello"}]
 :tools []
 :success-event-type [:pavlov.ai.llm/response-received :assistant]
 :failure-event-type [:pavlov.ai.llm/response-failed :assistant]}
```

A runtime bridge performs the side effect and then requests an event of the declared success or failure type, including the relevant correlation fields such as `:call-id` and `:conversation-id`.
