# Pavlov rewrite workflow

Use this workflow for rewriting an existing non-Pavlov system by first extracting Pavlov domain models. Default to **bounded contexts** as the primary model boundary. When boundaries are unclear, start from observable workflows and vocabulary clusters, then refine into bounded contexts as evidence accumulates. Each credible bounded context should have one main Pavlov domain model.

Within a bounded-context model, organize behavior around workflows, scenario families, lifecycle behaviors, and cross-cutting policies. Use smaller model-checking projections when needed for tractable verification, but keep them tied back to the one main bounded-context model.

The canonical model should become executable quickly. Use markdown to discover, review, and report; use Clojure/EDN Pavlov artifacts as the durable specification once a workflow slice is selected.

## 0. Charter the rewrite

Capture:

- source repository, stack, and runtime entry points
- rewrite objective and non-goals
- first bounded context
- initial workflow/scenario family inside that context
- primary happy-path business goal, if known
- critical risks
- available evidence: docs, tests, logs, traces, schemas, API specs, SMEs
- privacy/security constraints
- acceptance threshold for beginning implementation

Avoid starting with the whole application. Delimit a bounded context. If the context is not yet credible, pick a workflow or vocabulary cluster as a discovery seed, then refine the boundary as evidence accumulates.

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

Create model-checking projections only when the whole bounded-context model is too large to check at once, and only after the bounded context's primary happy-path spine has been named. Do not let the first model become a tiny endpoint, approval, validation, or persistence projection just because it is easy to check.

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

## 3. Select the happy-path spine and extract candidate artifacts

For each bounded context, identify one primary end-to-end happy-path event spine before trying to model the whole context. Prefer a spine with observable happy-path evidence from tests, traces, routes, flows, handlers, trigger paths, or SME review. The spine must reach the named business success outcome for the bounded context or selected scenario family.

Model the first happy path as end-to-end but initially coarse: include all known major lifecycle stages as domain events, while deferring internal detail inside those stages. Intermediate approvals, validations, CRUD operations, persistence writes, and task status changes can be steps inside the happy path, but they are usually not the whole happy path. If the full happy path is large, keep later stages abstract with coarse domain events instead of truncating the scenario at an intermediate milestone.

Validation rules and guards are excellent safety and policy evidence, but do not let them replace the positive behavioral spine. Extract them after the main success path is named, unless they are needed to understand the happy path's event vocabulary.

For the selected spine, record:

- business goal
- initiating event
- terminal success event
- ordered domain events needed to tell the success story
- known stage, task, or subprocess boundaries
- evidence for each event where available
- deferred internals and open questions

For each selected spine or scenario family, extract candidates into executable artifacts first when practical, using markdown only as a worksheet:

- domain events and commands in an event registry namespace or EDN data, starting with the happy-path spine events
- event payload schemas, preferably Malli when available
- positive scenarios as mostly linear Pavlov scenario bthreads, starting with one end-to-end happy path that reaches the named business outcome
- safety properties / invariants as safety bthreads or additive policy bthreads
- liveness or progress properties as hot-state progress bthreads where appropriate
- external collaborators and environment events as environment/state bthreads
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

## 5. Build the first executable model slice

Prepare the handoff for `pavlov-domain-modeling` as Clojure/EDN artifacts, not only markdown. The first executable slice should normally be the selected happy-path spine. If only an intermediate subflow can be executed first, label it explicitly as intermediate and do not present it as bounded-context completion.

- event registry with schemas, example payloads if useful, legacy names, evidence IDs, confidence, and status
- scenario namespace with completion events and mostly linear bthreads, including the main happy-path completion event
- rule/policy namespace for additive blocking or redirecting behavior
- safety namespace with forbidden states/traces and violation events
- liveness/progress namespace with trigger, eventual outcome, terminal exceptions, and hot-state semantics
- environment namespace/table for users, time, DB responses, queues, external APIs, files, and other collaborators
- model-check namespace/config with `:possible` completion events and included safety/progress checks
- abstraction decisions and bounded values
- unresolved questions

If markdown catalogs are required for review, generate or update them from the executable metadata whenever possible.

## 6. Expand and normalize Pavlov fragments

Use `pavlov-domain-modeling` to refine the executable artifacts. Use `pavlov-model-checking` to configure possibility, safety, progress, deadlock, and livelock checks.

See the other skills for what is authoritative for namespace shape, bthread structure, model-check configuration, and verification iteration.

This rewrite workflow remains responsible for preserving rewrite-specific metadata inside or alongside the executable model: evidence IDs, source citations, legacy names, abstraction decisions, accepted/rejected/deferred status, and unresolved questions. Avoid creating a second hand-maintained specification language in markdown after executable artifacts exist.

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
