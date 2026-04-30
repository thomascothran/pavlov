# Scenario identification

Use this guide when deciding what legacy behavior should become Pavlov scenarios, scenario families, or non-scenario model artifacts.

## What counts as a scenario

A scenario candidate should usually have:

- a triggering stimulus: request, message, job, timer, user action, or external-system event
- relevant preconditions or initial state
- a sequence of domain-meaningful events, decisions, or collaborations
- an observable outcome: response, terminal state, emitted message, side effect, or recorded fact
- evidence: test, replayable trace, source path, API contract, schema, documentation, or SME review
- a clear scope inside one bounded context, or an explicit cross-context collaboration boundary

Treat a scenario as a behavior the rewritten system must support or intentionally change, not as a transcript of implementation calls.

## What is not a scenario by itself

Do not promote these to scenarios unless they participate in a larger behavioral arc:

- a single CRUD endpoint with no domain-relevant outcome beyond persistence
- an isolated validation rule or guard
- a raw call chain, class method, service function, or controller action
- a database row change without business meaning
- a UI click path that does not change or observe domain behavior
- a broad user journey spanning unrelated goals or bounded contexts

These may still become evidence for events, invariants, state abstractions, environment behavior, or characterization tests.

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
- Prefer `expired verification cannot be completed` over `VerificationController rejects expired token`.
- Keep the legacy route, test, or function name in notes for traceability.

## Output for each scenario candidate

Record:

- scenario name and family
- trigger and preconditions
- event sequence in domain vocabulary
- expected outcome or terminal state
- completion event needed for model checking, if applicable
- evidence IDs and source citations
- confidence/status
- open questions, assumptions, or known contradictions
