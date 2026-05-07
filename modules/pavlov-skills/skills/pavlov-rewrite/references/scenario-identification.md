# Scenario identification

Use this guide when deciding what legacy behavior should become Pavlov scenarios, scenario families, or non-scenario model artifacts.

For Pavlov-first rewrites, prefer discovering the positive behavioral spine of a workflow before enumerating every guard and validation rule. The first scenario for a bounded context or selected scenario family should normally be an end-to-end happy path: it should reach the named business success outcome, not stop at the first easy approval, validation, endpoint, or persistence subflow. Happy paths and supported alternate outcomes should become executable scenario bthreads early; validation rules then compose around them as rule/policy or safety bthreads.

Describe the first happy path as "end-to-end but initially coarse": include all known major lifecycle stages, and represent poorly understood stages with coarse domain events until they are expanded.

## What counts as a scenario

A scenario candidate should usually have:

- a triggering stimulus: request, message, job, timer, user action, or external-system event
- relevant preconditions or initial state
- a sequence of domain-meaningful events, decisions, or collaborations
- an observable outcome: response, terminal state, emitted message, side effect, or recorded fact
- evidence: test, replayable trace, source path, API contract, schema, documentation, or SME review
- a clear scope inside one bounded context, or an explicit cross-context collaboration boundary

Treat a scenario as a behavior the rewritten system must support or intentionally change, not as a transcript of implementation calls.

For the first happy path, prefer coarse domain events over premature truncation. For example, if detailed fulfillment behavior is not yet understood, `:order/fulfilled` is a required placeholder in a Commerce happy path; ending at `:order/payment-approved` is only valid for a Payment bounded context or an explicitly intermediate Payment Approval scenario.

Generic anti-example for a `Commerce` bounded context:

```text
order-requested
order-created
payment-submitted
payment-approved
order-created-and-paid
```

This is not a Commerce happy path. It is a payment subflow. A Commerce happy path must continue through the known stages needed for the chosen business outcome, using coarse events where necessary, until that outcome is reached.

## What is not a scenario by itself

Do not promote these to scenarios unless they participate in a larger behavioral arc:

- a single CRUD endpoint with no domain-relevant outcome beyond persistence
- an isolated validation rule or guard
- a raw call chain, class method, service function, or controller action
- a database row change without business meaning
- a UI click path that does not change or observe domain behavior
- a broad user journey spanning unrelated goals or bounded contexts

These may still become evidence for events, invariants, state abstractions, environment behavior, or characterization tests.

Approval processes, validations, and status transitions often identify important events, but they should not substitute for the bounded context's positive business story. If modeled first for tactical reasons, name them as intermediate subflows such as `payment-approval-complete`, not as the main bounded-context completion.

## Scenario families

Group scenarios into a family when they share most of:

- business goal
- initiating stimulus class
- primary lifecycle or workflow
- actors and external collaborators
- vocabulary and bounded-context scope
- terminal outcome set
- safety and progress properties

Examples:

- checkout succeeds, payment fails, inventory unavailable, and order expires can be one `Checkout` scenario family.
- tenant isolation across many workflows is usually a cross-cutting policy projection, not a scenario family.
- nightly invoice generation and customer payment capture may be separate families if they have different triggers, lifecycles, and collaborators.

## When to split scenarios

Prefer one mostly linear scenario per supported outcome. Split scenarios when behavior differs by:

- terminal outcome or externally visible response
- failure mode or exception path
- authorization/tenancy path
- external collaborator response: success, denied, timeout, not-found, retryable failure
- compensation, retry, timeout, expiration, or dead-letter behavior
- materially different invariant or progress obligation
- different actor intent or business vocabulary

Avoid deeply branching one scenario to cover many outcomes. Branching belongs in the model composition and environment choices; individual positive scenarios should remain easy to review.

Do not split the first happy path so finely that no scenario reaches the business success outcome. Use separate bthreads for alternate outcomes after the main happy-path spine is present. A scenario named for the bounded context must include every known major stage needed for that bounded context's success outcome.

## Evidence priority

Use higher-confidence evidence first:

1. executable tests and replayable traces
2. API contracts and schemas
3. source code with file/line citations
4. structured logs, audit data, and outbox/event tables
5. docs, tickets, and runbooks
6. SME review
7. analyst/LLM inference, marked as an assumption until verified

A scenario can be a candidate with weak evidence, but accepted scenarios need non-inference evidence or explicit SME signoff.

## Naming guidance

Name scenarios by domain outcome, not framework shape:

- Prefer `authorized payment is captured` over `POST /payments/:id/capture returns 200`.
- Prefer `commerce order is fulfilled` over `OrderController create returns 201` or `PaymentGateway approval succeeds` when the bounded context is Commerce.
- Prefer `expired verification cannot be completed` over `VerificationController rejects expired token`.
- Keep the legacy route, test, or function name in notes for traceability.

Name completion events to match their real scope. Good names distinguish intermediate milestones from end-to-end business outcomes:

- `:commerce.scenarios/payment-approval-complete` for a payment subflow.
- `:commerce.scenarios/order-fulfilled-complete` for the end-to-end commerce success path.

Do not use a narrow completion event in prose or checks as if it proved a broader bounded-context lifecycle.

## Output for each scenario candidate

Record in executable metadata or code when possible:

- scenario name and family
- trigger and preconditions
- event sequence in domain vocabulary
- expected outcome or terminal state
- whether the scenario is end-to-end for the selected bounded context/scenario family or an intermediate subflow
- completion event needed for model checking, if applicable
- evidence IDs and source citations
- confidence/status
- open questions, assumptions, or known contradictions

Once selected for modeling, convert the candidate to a mostly linear Pavlov scenario bthread. Keep markdown as a generated or subordinate review view.
