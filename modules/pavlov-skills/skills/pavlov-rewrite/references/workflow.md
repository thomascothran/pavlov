# Pavlov rewrite workflow

Use this workflow for rewriting an existing non-Pavlov system by first extracting Pavlov domain models. Default to **bounded contexts** as the primary model boundary. When boundaries are unclear, start from observable workflows and vocabulary clusters, then refine into bounded contexts as evidence accumulates. Each credible bounded context should have one main Pavlov domain model.

Within a bounded-context model, organize behavior around workflows, scenario families, lifecycle behaviors, and cross-cutting policies. Use smaller model-checking projections when needed for tractable verification, but keep them tied back to the one main bounded-context model.

## 0. Charter the rewrite

Capture:

- source repository, stack, and runtime entry points
- rewrite objective and non-goals
- first bounded context
- initial workflow/scenario family or model-checking projection inside that context
- critical risks
- available evidence: docs, tests, logs, traces, schemas, API specs, SMEs
- privacy/security constraints
- acceptance threshold for beginning implementation

Avoid starting with the whole application. Delemit a bounded context. If the context is not yet credible, pick a workflow or vocabulary cluster as a discovery seed, then refine the boundary as evidence accumulates.

## 1. Inventory the legacy system

Create inventories for:

- external entry points: HTTP routes, GraphQL operations, RPCs, CLIs
- asynchronous entry points: queue consumers, event handlers, cron/background jobs
- persistence: tables, migrations, constraints, triggers, stored procedures, ORM models
- side effects: emails, webhooks, third-party APIs, files, caches, ledgers
- security: authentication, authorization, tenancy, rate limits, validation
- tests/specs/fixtures/snapshots
- logs, traces, audit tables, outbox/event tables

Use stack-specific playbooks and static heuristics for this phase.

## 2. Partition into bounded contexts, then behavioral projections

First identify bounded contexts when possible: boundaries within which vocabulary, rules, event meanings, and state concepts are internally consistent. When the legacy system lacks clear boundaries, begin with observable workflows and vocabulary clusters; promote them to bounded contexts only after evidence supports a coherent boundary.

For each credible bounded context, maintain one main Pavlov domain model.

Then organize that model behaviorally by:

- workflow or scenario family
- aggregate lifecycle behavior
- cross-cutting policy
- safety property group
- liveness/progress obligation group
- external collaboration pattern

Use aggregates/entities as supporting state abstractions inside the bounded context. They help identify invariants and consistency boundaries, but they are not the primary behavioral decomposition.

Create model-checking projections only when the whole bounded-context model is too large to check at once.

Good examples:

- `Checkout` as a workflow/scenario family involving cart, order, payment, inventory, and notification state.
- `Order lifecycle` as aggregate-centered behavior covering submit, pay, fulfill, cancel, and refund transitions.
- `Tenant isolation` as a cross-cutting policy projection that blocks forbidden events across workflows.

Do not create unrelated “mini domain models” for every endpoint, entity, or test file.

## 2a. Projection selection heuristics

Cluster behavior by:

- user journey / workflow
- scenario family
- lifecycle behavior
- cross-cutting policy
- data mutation boundary
- route/job/message entry point
- business vocabulary
- integration boundary
- likely model-check tractability

Each projection should fit in a reviewable/model-checkable subset of the bounded-context model. Large applications may contain multiple bounded contexts; each credible bounded context should have one main model and may have multiple verification projections.

For scenario selection and splitting rules, use `references/scenario-identification.md`.

## 3. Extract candidate artifacts

For each bounded context and selected projection, extract candidates into catalogs:

- domain events and commands
- positive scenarios
- safety properties / invariants
- liveness or progress properties
- external collaborators and environment events
- state variables and abstractions

Use these evidence priorities:

1. executable tests and replayable traces
2. API contracts and schemas
3. source code with file/line citations
4. structured logs/audit data
5. docs and tickets
6. SME review
7. LLM inference, clearly marked as assumption until verified

## 4. Normalize to a Pavlov vocabulary

Normalize implementation details into domain language:

- controllers/handlers become command events or external stimuli
- successful writes become domain facts/events
- error branches become rejected events or safety constraints
- status fields become explicit state transitions
- jobs and retries become progress/liveness obligations
- third-party calls become environment or side-effect events

Keep both names when useful:

- legacy name: `POST /api/v1/orders/:id/capture`
- Pavlov event: `:payment/capture-requested`

## 5. Draft the model handoff

Prepare a handoff for `pavlov-domain-modeling`:

- event catalog with schemas and example payloads
- scenario table with completion events
- safety table with forbidden states or traces
- liveness table with trigger, eventual outcome, terminal exceptions
- environment collaborator table
- abstraction decisions and bounded values
- unresolved questions

## 6. Build Pavlov fragments

Use `pavlov-domain-modeling` to turn accepted catalogs into Pavlov artifacts. Use `pavlov-model-checking` to configure possibility, safety, progress, deadlock, and livelock checks.

See the other skills for what is authoritative for namespace shape, bthread structure, model-check configuration, and verification iteration.

This rewrite workflow remains responsible for preserving the rewrite-specific handoff: evidence IDs, source citations, legacy names, abstraction decisions, accepted/rejected/deferred status, and unresolved questions.

## 7. Review and verify

Before implementation rewrite work:

- verify that every accepted model element has evidence or explicit SME signoff
- run model checks and record violations
- resolve contradictions or mark them as open decisions
- reject known legacy bugs rather than encoding them as requirements
- keep characterization tests for behavior not yet modeled

## 8. Rewrite incrementally

Implement one accepted bounded-context projection at a time, while preserving the single main model for the bounded context.

Use:

- Pavlov model checks for intended behavior
- characterization/differential tests for externally visible compatibility
- explicit decisions for behavior intentionally changed from the legacy system

Do not retire the legacy behavior until the model, characterization tests, or a written decision covers it.
