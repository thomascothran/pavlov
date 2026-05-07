---
name: pavlov-rewrite
description: Plan and execute Pavlov-first rewrites of existing non-Pavlov or legacy web/server codebases by extracting requirements, business rules, domain events, scenarios, safety properties, and liveness/progress properties from source code, tests, schemas, API contracts, logs, traces, and human review. Use when modernizing or rewriting an application with Pavlov, recovering requirements from an existing system, coordinating multi-session/multi-agent rewrite discovery, or turning legacy behavior into evidence-backed Pavlov domain models.
---

# Pavlov rewrite

Use this skill to convert an existing system into evidence-backed Pavlov domain models before implementation rewrite work begins. Default to **bounded contexts** as the primary model boundaries. When boundaries are unclear, start with observable workflows and vocabulary clusters, then refine into bounded contexts as evidence accumulates. Each bounded context should have one main Pavlov domain model with its own coherent vocabulary, scenarios, safety properties, liveness/progress properties, and environment contracts.

The goal is not to port code line-by-line. The goal is to discover the behavior that should survive the rewrite, express it as executable Pavlov events/scenarios/properties, verify the model, and only then use that model to guide implementation. Treat markdown catalogs as review and reporting artifacts, not the long-lived source of truth. Move accepted or actively modeled behavior into Clojure/EDN artifacts as early as possible.

## Core rules

- Treat extracted requirements as hypotheses until backed by evidence and review.
- Prefer deterministic extraction before LLM inference: routes, schemas, tests, API specs, migrations, logs, and traces.
- Attach provenance to every accepted event, scenario, invariant, and progress property.
- Prefer executable Clojure/EDN artifacts over hand-maintained markdown catalogs once a workflow slice is selected.
- For the first executable slice in a bounded context, identify the primary end-to-end happy-path event spine first. It must reach the bounded context's named business outcome, not an intermediate technical subflow, endpoint, validation, approval, or persistence fragment.
- Instruct agents to model the first happy path as "end-to-end but initially coarse." If the full happy path is large, include all known major stages as coarse domain events instead of truncating the scenario at an intermediate milestone.
- Use Malli or equivalent schemas for event payload contracts when available, but do not confuse event schemas with behavioral scenarios, safety, or progress.
- Keep markdown catalogs either temporary extraction worksheets or generated reports from executable model metadata.
- Separate legacy facts from rewrite decisions:
  - observed behavior
  - inferred intent
  - accepted requirement
  - rejected legacy bug
  - unresolved assumption
- Partition large systems into bounded contexts when boundaries are evident; otherwise start from observable workflows and vocabulary clusters, then refine.
- Maintain one main Pavlov domain model per bounded context once the context boundary is credible.
- Organize each bounded-context model behaviorally around workflows, scenario families, lifecycle behaviors, and cross-cutting policies.
- Define the main happy-path scenario before spending significant effort on alternates, rejection paths, validation rules, guards, safety properties, or model-checking projections.
- Use aggregates/entities as supporting state abstractions for invariants and consistency boundaries, not as the primary behavioral decomposition.
- Use model-checking projections only to keep verification tractable after the bounded context's happy-path spine is named; do not treat projections as competing domain models.
- Use existing Pavlov skills for Pavlov artifact design:
  - `pavlov-domain-modeling` for event/scenario/safety/progress namespace structure.
  - `pavlov-model-checking` for `:possible`, safety bthreads, hot-state liveness, and model-check iteration.

## Standard workflow

Read `references/workflow.md` first. Then load only the references needed for the current phase:

- `references/evidence-ledger.md` — durable multi-session claim tracking.
- `references/artifact-templates.md` — executable event/scenario/rule/safety/liveness artifact templates plus generated/review catalog views.
- `references/scenario-identification.md` — principles for finding and splitting scenarios.
- `references/agent-roles.md` — subagent division of labor and task packets.
- `references/model-readiness.md` — gates before implementation rewrite begins.
- `references/static-extraction-heuristics.md` — source/schema/API/test mining heuristics.
- `references/dynamic-trace-mining.md` — logs, traces, process mining, characterization tests.

For common stacks, load the relevant playbook under `references/stack-playbooks/`:

- `spring.md`
- `node-express-nest.md`
- `dotnet.md`
- `go.md`
- `salesforce.md`

## Required outputs

For each bounded context, produce or update canonical executable artifacts where possible:

- bounded-context charter and scope
- system inventory
- evidence ledger
- event registry namespace or EDN data with event metadata, payload schemas, legacy names, evidence, confidence, and status
- happy-path spine for the selected bounded context or scenario family, including initiating event, terminal success event, and ordered domain events
- scenario namespace with mostly linear Pavlov scenario bthreads and completion events
- rule/policy namespace with additive blocking or redirecting bthreads
- safety namespace with invariant-monitoring bthreads and violation events
- liveness/progress namespace with hot-state progress obligations where appropriate
- environment namespace for bounded user/time/DB/API/queue/external-system choices
- model-check namespace/config for the selected bounded context or projection
- open questions and assumptions
- model-readiness status

Markdown catalogs may still be produced for review, but they should be generated from or clearly subordinate to the executable artifacts once those artifacts exist.

For each model-checking projection inside a bounded context, record its scope, included workflows/properties, and relationship to the main bounded-context model.

## Verification stance

- Do not claim that source reading alone proves behavior. Execute tests, replay traces, inspect schemas, or ask for review when possible.
- Use model checking once Pavlov fragments exist.
- Preserve characterization or golden-master tests for behavior not yet modeled.
- Ask a separate auditor agent to check unsupported claims before treating a model as rewrite-ready.
