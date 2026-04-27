# Rewrite artifact templates

Use these templates for each bounded context and, when needed, each model-checking projection inside that bounded context.

## Bounded-context charter

| Field | Value |
| --- | --- |
| Bounded context |  |
| Business goal |  |
| In scope |  |
| Out of scope |  |
| Primary actors |  |
| Ubiquitous language / key terms |  |
| Primary aggregates/entities |  |
| Entry points |  |
| Main workflows/scenario families |  |
| Critical risks |  |
| Evidence sources |  |
| Open questions |  |

## Model-checking projection charter

Use this only when a subset of the bounded-context model must be checked separately for tractability.

| Field | Value |
| --- | --- |
| Projection name |  |
| Parent bounded context |  |
| Included workflows/scenarios |  |
| Included safety/liveness properties |  |
| Included aggregates/state abstractions |  |
| Excluded behavior and why |  |
| Relationship to main model |  |

## System inventory

| Kind | Name | Location | Notes | Evidence ID |
| --- | --- | --- | --- | --- |
| route/job/message/schema/test/log/integration |  |  |  |  |

## Event catalog

| Pavlov event | Kind | Legacy source | Payload fields | Evidence | Confidence | Status | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `:order/submitted` | command/domain/env/side-effect |  |  |  |  |  |  |

Kinds:

- `command` — external request to change or query behavior.
- `domain` — meaningful business fact or state transition.
- `environment` — user/time/external-system stimulus.
- `side-effect` — email, webhook, API call, ledger write, file output.
- `completion` — namespaced scenario completion marker for model checking.
- `violation` — invariant violation event with `:invariant-violated true`.

## Scenario catalog

| Scenario | Given | When | Then / trace | Completion event | Evidence | Status | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Happy path |  |  |  | `:checkout.scenarios/foo-complete` |  |  |  |

Guidance:

- Prefer one linear scenario per supported outcome.
- Include failure and alternate outcomes.
- End each model-check scenario with a unique namespaced completion event.
- Link scenario steps to event catalog rows.

## Safety property catalog

| Property | Forbidden condition or trace | Detection evidence | Negative example/test | Pavlov violation event | Status |
| --- | --- | --- | --- | --- | --- |
|  |  |  |  | `:checkout.safety/invariant-violated` |  |

Safety properties say “bad things never happen.” Sources include validations, guards, authorization checks, assertions, DB constraints, and production incidents.

## Liveness/progress property catalog

| Property | Trigger | Must eventually reach | Terminal exceptions | Time/window semantics | Evidence | Status |
| --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |  |

Liveness properties say “once started, something must eventually happen.” Always define terminal failure states and exceptions.

## External collaborator catalog

| Collaborator | Type | Operations | Pavlov role | Failure modes | Evidence |
| --- | --- | --- | --- | --- | --- |
|  | DB/queue/API/cache/email/time/user |  | environment/state bthread/side-effect bthread |  |  |

## Assumptions and decisions

| ID | Type | Statement | Reason | Owner | Date | Status |
| --- | --- | --- | --- | --- | --- | --- |
| DEC-001 | assumption/decision/rejected-bug |  |  |  |  |  |

## Model handoff checklist

- [ ] Bounded-context charter completed.
- [ ] Projection charter completed if checking only a subset.
- [ ] Event catalog has evidence and statuses.
- [ ] Each scenario uses event catalog events.
- [ ] Each scenario has a completion event.
- [ ] Safety properties identify violation events.
- [ ] Liveness properties identify terminal exceptions.
- [ ] External collaborators have environment/state roles.
- [ ] All accepted claims cite ledger IDs.
- [ ] Open questions are explicit.
