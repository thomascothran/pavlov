# Model readiness checklist

Use this checklist before starting implementation rewrite work for a bounded context or a model-checking projection inside it.

## Evidence coverage

- [ ] Every accepted event has source/test/schema/trace/doc/SME evidence.
- [ ] Every accepted scenario has executable, trace, documentation, or SME evidence.
- [ ] The selected bounded context or scenario family has at least one end-to-end happy-path scenario that reaches the named business outcome, or the absence of one is explicitly marked as a blocker.
- [ ] Every accepted safety property has evidence for the forbidden condition.
- [ ] Every accepted liveness property defines trigger, eventual outcome, and terminal exceptions.
- [ ] Claims based only on LLM/analyst inference remain assumptions.
- [ ] Contradictions are resolved, deferred, or explicitly represented as decisions.

## Pavlov shape

- [ ] Executable Clojure/EDN artifacts are canonical for modeled workflow slices.
- [ ] Markdown catalogs, if present, are generated from or subordinate to executable metadata.
- [ ] Event names are domain-oriented, not framework-oriented.
- [ ] Event names belong to the bounded context's ubiquitous language.
- [ ] Event registry entries include evidence, confidence, status, and legacy/source names where useful.
- [ ] Event payload schemas exist for modeled payloads where shape matters, preferably Malli when available.
- [ ] Event payloads are bounded enough for model checking.
- [ ] Happy-path spine events are present in the event registry before extensive alternate, failure, validation, or safety modeling.
- [ ] Positive scenarios are mostly linear Pavlov bthreads.
- [ ] At least one positive scenario reaches the named business success outcome for the selected bounded context or scenario family and includes every known major lifecycle stage.
- [ ] Intermediate subflows are labeled as intermediate and not treated as bounded-context completion.
- [ ] Each scenario has a unique completion event for `:possible` checks, and each completion event name matches its real scope.
- [ ] Safety violations use `:invariant-violated true`.
- [ ] Additive business constraints are represented as rule/policy bthreads instead of branching the positive scenario when possible.
- [ ] Progress requirements are represented with hot-state liveness where appropriate.
- [ ] Environment bthreads model users, time, DB responses, queues, and external systems.
- [ ] State-space bounds are explicit.

## Verification

- [ ] Model checker runs for the bounded context or selected projection.
- [ ] `:possible` covers scenario completion events, including the main happy-path completion event when one is in scope.
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

Events/scenarios/properties are drafted with evidence. For selected workflow slices, at least some canonical event registry and scenario/rule/safety artifacts exist, but review/model checking is incomplete. A candidate model should identify the happy-path spine, even if some later events are coarse or marked `needs-evidence`.

### Level 2 — Reviewable model

The executable model is coherent enough for SME review and model-check iteration. Markdown views may be generated for review, but should not be the only representation of modeled behavior. At this level, the selected bounded context or scenario family should normally have an executable end-to-end happy path with a `:possible` check.

### Level 3 — Rewrite-ready bounded context/projection

Accepted model passes required checks, unresolved assumptions are non-blocking, and characterization coverage exists for unmodeled compatibility concerns.

### Level 4 — Verified rewrite bounded context/projection

Implementation passes Pavlov model checks and agreed characterization/differential tests.
