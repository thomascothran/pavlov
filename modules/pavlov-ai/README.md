# Pavlov AI

Pavlov AI provides bthreads and event conventions for composing LLM-backed agents inside Pavlov behavioral programs.

The main design rule is that an agent bthread should stay pure: it requests LLM/tool work as Pavlov events, and separate runtime bthreads or subscribers perform side effects and answer with configured response event types.

## Identifier scopes

LLM systems often need several different identifiers. They should not all be collapsed into one `id`.

| Identifier | Scope | Shared by | Used for | Example |
|---|---:|---|---|---|
| `:run-id` | One bprogram execution/runtime instance | Everything in that single program run | Tracing, logs, replay, associating events with a concrete runtime execution | `#uuid "..."` |
| `:session-id` | One user/application session | Multiple bprogram runs and/or multiple agents serving the same user/session | UI/session continuity, auth/user context, coarse-grained audit grouping | `:pi-session/123` |
| `:agent-id` / constructor `:id` | One logical agent bthread | Events intended for that specific agent | Deriving addressed event types, identifying the agent that requested an LLM call | `:assistant` |
| `:conversation-id` | One logical conversation/task with an agent | Multiple LLM calls and tool calls within the same agent conversation | Grouping turns, tool rounds, and the final response for one ongoing dialogue/task | `:conversation/main` |
| `:call-id` | One specific LLM call | Exactly one request/response/failure cycle | Matching an LLM runtime response to the pending call that caused it | `[:assistant :conversation/main 1]` |
| `:tool-call-id` | One specific tool call requested by the model | Exactly one tool request/result/failure cycle | Matching tool results back into an LLM tool loop | `"call_abc123"` |

Notes:

- A Pi session, if present, maps most naturally to `:session-id`, not `:conversation-id`.
- A single bprogram run may contain several agents. Those agents may share a `:session-id` and `:run-id`, but each has its own `:agent-id`.
- A single agent may handle multiple conversations over time. Each should use a distinct `:conversation-id`.
- `:call-id` is intentionally narrower than `:conversation-id`; every retry or follow-up LLM call should get a distinct `:call-id`.

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
