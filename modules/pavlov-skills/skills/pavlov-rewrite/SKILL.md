---
name: pavlov-rewrite
description: Plan and execute Pavlov-first rewrites of existing non-Pavlov or legacy web/server codebases by extracting requirements, business rules, domain events, scenarios, safety properties, and liveness/progress properties from source code, tests, schemas, API contracts, logs, traces, and human review. Use when modernizing or rewriting an application with Pavlov, recovering requirements from an existing system, coordinating multi-session/multi-agent rewrite discovery, or turning legacy behavior into evidence-backed Pavlov domain models.
---

# Pavlov rewrite

Use this skill to convert an existing system into evidence-backed Pavlov domain models before implementation rewrite work begins. Treat **bounded contexts** as the primary model boundaries: each bounded context should have one main Pavlov domain model with its own coherent vocabulary, scenarios, safety properties, liveness/progress properties, and environment contracts.

The goal is not to port code line-by-line. The goal is to discover the behavior that should survive the rewrite, express it as Pavlov events/scenarios/properties, verify the model, and only then use that model to guide implementation.

## Core rules

- Treat extracted requirements as hypotheses until backed by evidence and review.
- Prefer deterministic extraction before LLM inference: routes, schemas, tests, API specs, migrations, logs, and traces.
- Attach provenance to every accepted event, scenario, invariant, and progress property.
- Separate legacy facts from rewrite decisions:
  - observed behavior
  - inferred intent
  - accepted requirement
  - rejected legacy bug
  - unresolved assumption
- Partition large systems into bounded contexts first.
- Maintain one main Pavlov domain model per bounded context.
- Organize each bounded-context model behaviorally around workflows, scenario families, lifecycle behaviors, and cross-cutting policies.
- Use aggregates/entities as supporting state abstractions for invariants and consistency boundaries, not as the primary behavioral decomposition.
- Use model-checking projections only to keep verification tractable; do not treat them as competing domain models.
- Use existing Pavlov skills for Pavlov artifact design:
  - `pavlov-domain-modeling` for event/scenario/safety/progress namespace structure.
  - `pavlov-model-checking` for `:possible`, safety bthreads, hot-state liveness, and model-check iteration.

## Standard workflow

Read `references/workflow.md` first. Then load only the references needed for the current phase:

- `references/evidence-ledger.md` — durable multi-session claim tracking.
- `references/artifact-templates.md` — event/scenario/safety/liveness catalog templates.
- `references/agent-roles.md` — subagent division of labor and task packets.
- `references/model-readiness.md` — gates before implementation rewrite begins.
- `references/static-extraction-heuristics.md` — source/schema/API/test mining heuristics.
- `references/dynamic-trace-mining.md` — logs, traces, process mining, characterization tests.
- `references/state-of-art.md` — background on current research and tool families.

For common stacks, load the relevant playbook under `references/stack-playbooks/`:

- `rails.md`
- `django.md`
- `spring.md`
- `node-express-nest.md`
- `dotnet.md`
- `go.md`

## Required outputs

For each bounded context, produce or update:

- bounded-context charter and scope
- system inventory
- evidence ledger
- event catalog
- scenario catalog
- safety property catalog
- liveness/progress property catalog
- external collaborator catalog
- open questions and assumptions
- model-readiness status

For each model-checking projection inside a bounded context, record its scope, included workflows/properties, and relationship to the main bounded-context model.

## Verification stance

- Do not claim that source reading alone proves behavior. Execute tests, replay traces, inspect schemas, or ask for review when possible.
- Use model checking once Pavlov fragments exist.
- Preserve characterization or golden-master tests for behavior not yet modeled.
- Ask a separate auditor agent to check unsupported claims before treating a model as rewrite-ready.
