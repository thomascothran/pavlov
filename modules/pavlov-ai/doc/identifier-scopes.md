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
