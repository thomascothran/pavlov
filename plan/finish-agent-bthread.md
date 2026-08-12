# Finish the Pavlov AI Agent and LLM Runtime

## Objective

Complete a non-streaming LLM runtime around the pure agent bthread while preserving deterministic correlation across LLM calls, action requests/responses, and message history.

The remaining work should be delivered as behaviorally complete slices. Each phase defines observable behavior and interface contracts; implementation details remain replaceable.

## Architectural invariants

These apply to every remaining phase:

1. The agent bthread remains pure.
2. External work is requested and answered with Pavlov events.
3. Every LLM outcome retains its originating `:agent-name` and `:llm-call-id`.
4. One accepted LLM call produces no more than one success/failure outcome.
5. Raw HTTP responses and credentials never enter the agent-facing event contract.
6. Provider terminology and wire formats remain behind a provider boundary.
7. Provider action-call IDs remain distinct from deterministic Pavlov IDs.
8. Blocking provider work never blocks the Pavlov event loop.
9. Expected provider/runtime failures are data; unexpected programming defects are not silently converted into provider failures.
10. The pure event protocol remains model-checkable without a live provider.

## Settled design

- The runtime side effect will be performed by a filtered subscriber.
- The subscriber reacts only to `:pavlov.ai/call-llm`.
- Provider operations dispatch by provider keyword.
- HTTP transport is injected as `:post!` inside provider options and follows Hato's two-argument call shape.
- `chat-completion!` performs a non-streaming HTTP call and returns the decoded HTTP envelope without semantic translation.
- `normalize-chat-completion` is pure and translates a decoded provider response into a provider-neutral success or Cognitect anomaly.
- Normalization occurs before an agent-facing event is submitted.
- LLM call IDs are deterministic vectors shaped as `[agent-name llm-call-number]`.
- Correlation metadata is structured and is not embedded in model-visible text.
- Pavlov uses action terminology; provider-native tool/function terminology stays at the provider boundary.
- The first version supports at most one action per LLM response. Multiple actions are rejected.
- Streaming and multi-action collection are deferred.

## Completed

### Agent validation and correlation

- [x] Require request schemas for configured actions.
- [x] Reject undeclared actions.
- [x] Validate action arguments and return structured violations.
- [x] Reject multiple actions before forwarding any action.
- [x] Return rejected-action feedback to the LLM.
- [x] Add deterministic `:llm-call-id` values to all LLM call events.
- [x] Preserve call IDs through LLM responses, assistant history, action requests/responses, action-result history, rejected actions, and rejection history.
- [x] Preserve `:action-type` through action requests/responses and result history.

### Model checking and deterministic environment

- [x] Model-check LLM call-ID sequencing and request/response correlation.
- [x] Model-check assistant, action-result, and rejection history correlation.
- [x] Model-check that undeclared, schema-invalid, and multiple actions are not forwarded.
- [x] Extract the correlation monitor into `agent/safety/llm_id_tracing.clj`.
- [x] Add deterministic handlers for `:email/list`, `:email/send`, and `:text-response`.
- [x] Make simulated LLM responses echo their originating call IDs.
- [x] Re-run the current agent model check: 1 test, 1 assertion, 0 failures.

### OpenAI-compatible provider operation

- [x] Add provider-dispatched `chat-completion!`.
- [x] Implement the `:openai-compatible` method with injected `:post!`.
- [x] Support configurable URL, bearer credentials, and additional headers.
- [x] Force non-streaming requests.
- [x] Retain HTTP error responses for inspection when their bodies decode successfully.
- [x] Decode valid JSON response bodies while preserving the HTTP envelope.
- [x] Add a delayed, REPL-oriented OpenRouter example.

### Provider normalization

- [x] Add provider-dispatched `normalize-chat-completion`.
- [x] Add reusable anomaly detection and short-circuiting transformation behavior.
- [x] Normalize text responses.
- [x] Normalize provider tool calls into action calls with decoded arguments.
- [x] Preserve opaque provider action-call IDs.
- [x] Normalize finish reason, usage, response ID, and model metadata.
- [x] Normalize decoded provider error responses with Cognitect anomaly categories.
- [x] Return malformed-response anomalies for missing messages and malformed action arguments.
- [x] Remove catch-and-rethrow validation flow.
- [x] Verify provider behavior: 6 tests, 10 assertions, 0 failures.
- [x] Verify touched provider code with `clj-kondo`: 0 errors, 0 warnings.

## Known current behavior

These describe the present implementation, not necessarily the desired final behavior:

- The agent consumes `{:response {:actions [...]}}`; normalized provider completions do not yet implement that interface.
- Plain normalized text has no path through the agent.
- `:text-response` is currently an ordinary action whose result causes another LLM call.
- No observable agent-completion event is emitted.
- The current model assumes one outstanding LLM call for the modeled agent.
- Deadlock checking is disabled.
- Malformed whole-response JSON can throw before normalization.
- Non-JSON HTTP error bodies can fail before their status is normalized.
- No real provider subscriber exists.

# Remaining phases

## Phase 1 — Define the behavioral contracts

### Goal

Make the boundaries between the agent, provider adapter, and runtime explicit before connecting them.

### Steps

1. Define the **LLM Call Request** interface.
   - It carries an immutable message/action snapshot.
   - It identifies the provider and provider-neutral options.
   - It declares addressed success and failure event types.
   - It carries `:agent-name` and `:llm-call-id`.

2. Define the **Normalized Completion** interface.
   - It distinguishes text from action calls without exposing a provider wire shape.
   - Action calls contain decoded arguments and an opaque provider call ID.
   - Expected failures use a documented Cognitect anomaly shape.
   - Optional usage/provider metadata cannot affect domain correctness.

3. Define the **Agent LLM Outcome** interface.
   - It maps a normalized completion to the shape consumed by the agent.
   - It defines how text, action calls, malformed completions, and provider failures are represented.
   - Success and failure outcomes retain the original call correlation.

4. Define the **Action Continuation** interface.
   - A provider action-call ID can be recovered when its action result is sent back to the provider.
   - The provider ID does not replace `:llm-call-id` or future `:action-request-id`.
   - Provider-native IDs do not leak into unrelated action handlers.

5. Decide lifecycle behavior that changes these contracts.
   - Whether plain text completes an invocation, becomes a distinguished action, or is rejected.
   - Whether `:text-response` completes or continues the LLM loop.
   - Whether one agent is single-flight, queues invocations, or partitions conversations.
   - Which LLM failures are retryable and which complete the invocation as failed.

### Exit criteria

- Representative text, action, and failure examples can be expressed entirely with these interfaces.
- No example requires raw HTTP/provider response data.
- Every outcome can be matched to exactly one originating call.
- The current single-action restriction is explicit.

## Phase 2 — Complete the pure provider boundary

### Goal

Translate between the contracts without performing side effects.

### Steps

1. Translate an LLM Call Request into a provider request.
   - Convert message history into provider messages.
   - Convert declared actions and schemas into provider action/tool declarations.
   - Preserve the immutable snapshot exactly; do not consult mutable agent state.

2. Define reversible provider-safe action names.
   - Namespaced Pavlov action types can be represented in providers with restricted function-name alphabets.
   - A returned provider name resolves to exactly one declared Pavlov action.
   - Unknown names remain rejectable by the existing agent validation path.

3. Translate a Normalized Completion into an Agent LLM Outcome.
   - Text follows the Phase 1 decision.
   - Action calls become the existing Pavlov action shape.
   - Provider call IDs remain available for the continuation round trip.

4. Translate action results back into provider continuation messages.
   - The correct opaque provider call ID accompanies the result.
   - Action results remain correlated with their Pavlov call/action identity.

5. Complete pure error conversion.
   - Malformed whole-response JSON becomes a fault anomaly.
   - Non-JSON HTTP errors retain status and become categorized anomalies.
   - Request-encoding failures have a defined boundary.

### Exit criteria

- Pure fixture tests cover text, one action, action result continuation, provider error, malformed response, and unknown action name.
- A complete action round trip preserves both Pavlov and provider correlation IDs.
- Replacing the HTTP transport does not change any translation test.

## Phase 3 — Complete the agent lifecycle behavior

### Goal

Make success, action continuation, completion, and failure observable and model-checkable before adding the live runtime.

### Steps

1. Consume the Agent LLM Outcome interface in the agent.
2. Implement the chosen plain-text and `:text-response` behavior.
3. Emit an observable completion or failure outcome for the original invocation.
4. Implement the chosen single-flight, queueing, or conversation-partition behavior.
5. Define retry behavior in terms of anomaly category and retry limits.
6. Reject or ignore stale, duplicate, and wrongly correlated outcomes.
7. Represent terminal success/failure so deadlock checking can distinguish completion from a stuck program.
8. Extend deterministic scenarios and safety/liveness properties for the chosen behavior.

### Exit criteria

- Model scenarios cover direct completion, one action round trip, action rejection/recovery, provider failure, and malformed completion.
- Safety checks reject stale, duplicate, and mismatched outcomes.
- Progress properties show that accepted invocations complete or fail under stated environment assumptions.
- Deadlock checking is enabled and passes for completed scenarios.

## Phase 4 — Add the runtime subscriber

### Goal

Connect the already-defined event protocol to real provider calls without adding domain policy to the side-effect layer.

### Steps

1. Observe only LLM Call Request events; unrelated events perform no work.
2. Capture the immutable call snapshot before starting external work.
3. Invoke the selected provider operation outside the event-loop thread.
4. Normalize the provider result before constructing an agent-facing event.
5. Convert expected transport/decode failures into the Phase 1 anomaly contract.
6. Submit exactly one addressed success/failure event for each accepted call.
7. Isolate calls so delay or failure in one call cannot corrupt another call's correlation.
8. Keep credentials and raw sensitive payloads out of events and errors.

The subscriber's construction API remains intentionally unspecified until the Phase 1 contracts identify the capabilities it actually needs.

### Exit criteria

- Tests with fake provider work prove filtering, non-blocking execution, correlation, and exactly-one outcome behavior.
- Delayed calls retain independent snapshots.
- Concurrent runtime calls can complete in reverse order without swapping responses.
- One failed call does not remove or poison the subscriber.

## Phase 5 — Operational hardening

### Goal

Make runtime behavior bounded, diagnosable, and safe under realistic failures.

### Steps

1. Define timeout and cancellation behavior at the call boundary.
2. Define concurrency limits and overload/backpressure outcomes.
3. Define ownership and shutdown behavior for outstanding work.
4. Verify categorization for authentication, validation, rate limit, timeout, availability, malformed response, and unexpected provider errors.
5. Ensure retries cannot duplicate successful outcomes or violate call correlation.
6. Define observability metadata that is useful without exposing credentials or full sensitive prompts.
7. Add an opt-in live OpenRouter smoke scenario without making normal tests depend on network access.

### Exit criteria

- Work is bounded under slow or unavailable providers.
- Shutdown/cancellation has defined outcomes.
- Failure categories drive deterministic retry/final-failure behavior.
- Logs/events contain correlation data but no credentials.

## Phase 6 — Documentation and release readiness

### Goal

Make the supported contract and limitations clear to users and future provider implementations.

### Steps

1. Update `modules/pavlov-ai/README.md` to match actual event names and fields.
2. Document the filtered-subscriber architecture and the pure-provider boundary.
3. Correct subscriber documentation so its runtime context matches actual Pavlov behavior.
4. Document normalized completion/anomaly interfaces.
5. Document single-action, non-streaming, lifecycle, retry, and concurrency limits.
6. State the supported runtime platforms and provider compatibility scope.
7. Provide a minimal composition example using a fake provider and an opt-in OpenRouter example.

### Exit criteria

- Public examples use only supported interfaces.
- A new provider implementation can identify which interfaces it must satisfy.
- Unsupported behavior is explicit rather than silently accepted.

## Deferred work

- `:action-request-id [llm-call-id action-index]`.
- Multiple actions in one LLM response.
- Collection of multiple action results before the next LLM call.
- Model checking of repeated same-type actions and all result orderings.
- Streaming responses.
- Native-provider adapters when OpenAI-compatible APIs are insufficient.

## Coupling and pluggability checks

- `agent -> Pavlov AI event contracts`: essential.
- `runtime subscriber -> BProgram submission interface`: essential.
- `runtime subscriber -> provider operation interface`: essential.
- `provider implementation -> injected HTTP transport`: replaceable.
- `pure translation -> provider-neutral and agent contracts`: explicit boundary.
- The agent must not depend on Hato, provider SDKs, raw HTTP responses, or provider wire shapes.
- Provider implementations must not depend on agent state.
- Runtime execution/backpressure policy must be replaceable without changing agent behavior.
- The deterministic environment must remain a substitute for the live runtime in model checking.
