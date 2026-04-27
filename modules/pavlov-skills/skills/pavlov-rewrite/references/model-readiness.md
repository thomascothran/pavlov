# Model readiness checklist

Use this checklist before starting implementation rewrite work for a bounded context or a model-checking projection inside it.

## Evidence coverage

- [ ] Every accepted event has source/test/schema/trace/doc/SME evidence.
- [ ] Every accepted scenario has executable, trace, documentation, or SME evidence.
- [ ] Every accepted safety property has evidence for the forbidden condition.
- [ ] Every accepted liveness property defines trigger, eventual outcome, and terminal exceptions.
- [ ] Claims based only on LLM/analyst inference remain assumptions.
- [ ] Contradictions are resolved, deferred, or explicitly represented as decisions.

## Pavlov shape

- [ ] Event names are domain-oriented, not framework-oriented.
- [ ] Event names belong to the bounded context's ubiquitous language.
- [ ] Event payloads are bounded enough for model checking.
- [ ] Positive scenarios are mostly linear.
- [ ] Each scenario has a unique completion event for `:possible` checks.
- [ ] Safety violations use `:invariant-violated true`.
- [ ] Progress requirements are represented with hot-state liveness where appropriate.
- [ ] Environment bthreads model users, time, DB responses, queues, and external systems.
- [ ] State-space bounds are explicit.

## Verification

- [ ] Model checker runs for the bounded context or selected projection.
- [ ] `:possible` covers scenario completion events.
- [ ] Safety bthreads are included in the check config.
- [ ] Liveness/progress checks are included or explicitly deferred.
- [ ] Deadlocks/livelocks are understood, fixed, or intentionally disabled with a note.
- [ ] Characterization/differential tests exist for important behavior not yet modeled.

## Human decisions

- [ ] Domain vocabulary reviewed.
- [ ] Critical invariants reviewed.
- [ ] Intentional changes from legacy behavior recorded.
- [ ] Rejected legacy bugs recorded.
- [ ] Privacy/security constraints handled.
- [ ] Remaining open questions are not blockers or have owners.

## Rewrite readiness levels

### Level 0 — Inventory only

Entry points and data structures are known, but no model claims are accepted.

### Level 1 — Candidate model

Events/scenarios/properties are drafted with evidence, but review/model checking is incomplete.

### Level 2 — Reviewable model

The model is coherent enough for SME review and model-check iteration.

### Level 3 — Rewrite-ready bounded context/projection

Accepted model passes required checks, unresolved assumptions are non-blocking, and characterization coverage exists for unmodeled compatibility concerns.

### Level 4 — Verified rewrite bounded context/projection

Implementation passes Pavlov model checks and agreed characterization/differential tests.
