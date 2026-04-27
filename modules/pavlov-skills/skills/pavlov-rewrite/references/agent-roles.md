# Subagent roles for rewrite discovery

Use subagents when the codebase is large, evidence sources are diverse, or multiple searches can run independently.

Every subagent should return evidence, interpretation, gaps, and completion status. Require citations for source claims.

## Common roles

### System mapper

Find routes, handlers, jobs, message consumers, CLI entry points, integrations, and configuration.

Output:

- entry point inventory
- likely bounded-context boundaries
- candidate model-checking projections
- external collaborators
- files/symbols needing deeper analysis

### Persistence explorer

Find schemas, migrations, constraints, ORM models, triggers, stored procedures, status fields, audit/outbox tables, and write paths.

Output:

- entity/aggregate inventory as supporting state abstractions
- candidate state variables
- invariants from constraints
- candidate status-transition graph

### Test/spec miner

Mine unit, integration, acceptance, BDD, fixture, and snapshot tests.

Output:

- scenario candidates
- assertions as safety candidates
- test data examples
- characterization test opportunities

### Trace/log miner

Analyze logs, traces, audit events, and session data when available.

Output:

- observed event sequences grouped by case/correlation ID
- common and rare variants
- timing/progress candidates
- missing correlation or observability gaps

### Safety miner

Search for validations, authorization checks, guards, assertions, error branches, security controls, and DB constraints.

Output:

- safety property candidates
- evidence for forbidden states/traces
- negative tests or missing tests
- ambiguous business-vs-security decisions

### Liveness miner

Search for workflows that must progress: queues, retries, lifecycle status changes, expirations, reminders, settlement, shipment, notification, timeout, dead-letter behavior.

Output:

- progress property candidates
- trigger and eventual outcome
- terminal exceptions
- time/window semantics needing review

### Model drafter

Translate accepted catalogs into a handoff for `pavlov-domain-modeling`.

Output:

- event schemas
- linear scenario plan
- safety/liveness bthread plan
- environment/state abstraction plan
- model-check configuration sketch

### Evidence auditor

Check that proposed model elements are supported.

Output:

- unsupported claims
- contradicted claims
- claims with only inference evidence
- missing tests/traces
- recommendations for review or execution

## Task packet template

```markdown
- Overarching Goal: Rewrite <system/bounded-context> by extracting an evidence-backed Pavlov model first.
- Target subagent: <role>
- Subagent objective: <specific artifact to produce>
- Phase or mode: discovery / verification / audit
- Planning document: <path>
- Bounded context: <name and scope>
- Projection scope: <workflow/scenario family/policy/lifecycle subset, if applicable>
- Inputs: <known files, prior inventories, evidence IDs>
- Relevant skills: pavlov-rewrite, plus stack/Pavlov skills as needed
- Hard constraints:
  - Research only unless explicitly asked to edit planning artifacts.
  - Do not modify production or test code.
  - Cite file paths, line numbers, tests, schemas, or trace IDs.
  - Mark unsupported inference as assumption.
- Required verification commands: <tests/replay/queries if applicable>
- Definition of done: <tables/claims/status expected>
- Shortcircuit conditions: <missing files, impossible setup, unsafe data>
- Output required:
  - Evidence
  - Interpretation
  - Gaps
  - Completion status
```

## Coordination rules

- Keep a work queue with owner, scope, status, and blockers.
- Prefer bounded-context and projection-specific tasks over whole-repo prompts.
- Use an auditor after synthesis tasks.
- Merge outputs through the evidence ledger, not conversation memory.
