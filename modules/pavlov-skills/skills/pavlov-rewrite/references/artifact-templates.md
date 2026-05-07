# Rewrite artifact templates

Use these templates for each bounded context and, when needed, each model-checking projection inside that bounded context.

Prefer executable Clojure/EDN artifacts as the canonical model once a workflow slice is selected. Markdown tables are useful during discovery and review, but should be generated from or subordinate to executable metadata whenever practical.

For the first model slice in a bounded context, capture the happy-path spine before detailed alternates, validations, or safety properties. The spine should be end-to-end for the selected business outcome, but initially coarse where details are unknown.

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

## Happy-path spine

Use this before creating the first executable scenario for a bounded context or scenario family. This is a discovery/review view; once selected, move the events and scenario into executable Clojure/EDN artifacts.

| Field | Value |
| --- | --- |
| Bounded context |  |
| Scenario family |  |
| Business goal |  |
| Initiating event |  |
| Terminal success event |  |
| Happy-path event sequence |  |
| Required collaborators |  |
| Known stage/task/subprocess boundaries |  |
| Deferred internals / coarse events |  |
| Evidence IDs |  |
| Open questions |  |

Guidance:

- The sequence must reach the business success outcome for the chosen scope.
- Include every known major lifecycle stage needed for that success outcome.
- Use coarse events for poorly understood later stages instead of stopping early.
- Mark weakly evidenced spine events as `candidate` or `needs-evidence`; do not silently omit them just because validations or approvals have stronger source evidence.
- Treat intermediate approval, validation, or persistence subflows as parts of the spine unless the selected scenario family is explicitly limited to that subflow.

## Model-checking projection charter

Use this only when a subset of the bounded-context model must be checked separately for tractability. Do not create a projection as the first model shape until the parent bounded context's happy-path spine has been named.

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

## Event registry

Canonical form should be a namespace or EDN value with event metadata and payload schemas. Use Malli schemas when available. For the first model slice, register all events needed by the happy-path spine before adding extensive alternate, failure, validation, or policy events.

```clojure
(ns your.context.events)

(def event-registry
  {:your.context/order-submitted
   {:kind :command
    :schema [:map
             [:order-id string?]
             [:actor-id string?]]
    :legacy/source "POST /orders/:id/submit"
    :evidence #{:EV-001}
    :confidence :high
    :status :candidate
    :notes "Legacy source name retained for traceability."}})
```

Do not add local event-constructor helpers here unless the project lacks one; prefer the project's existing event construction function.

Generated/review markdown form:

| Pavlov event | Kind | Legacy source | Payload schema/fields | Evidence | Confidence | Status | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `:order/submitted` | command/domain/env/side-effect |  |  |  |  |  |  |

Kinds:

- `command` — external request to change or query behavior.
- `domain` — meaningful business fact or state transition.
- `environment` — user/time/external-system stimulus.
- `side-effect` — email, webhook, API call, ledger write, file output.
- `completion` — namespaced scenario completion marker for model checking.
- `violation` — invariant violation event with `:invariant-violated true`.

## Scenario artifacts

Canonical form should be Pavlov scenario bthreads. Keep scenarios mostly linear and end each model-check scenario with a unique namespaced completion event. The first scenario should normally be the end-to-end happy path for the selected bounded context or scenario family. If an intermediate subflow is modeled first, name and document it as intermediate.

```clojure
(ns your.context.scenarios
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(def happy-path-complete
  :your.context.scenarios/happy-path-complete)

(defn happy-path []
  (b/bids
   [{:wait-on #{:your.context/requested}}
    {:request #{{:type :your.context/accepted}}}
    ;; Use additional coarse domain events here when the real happy path has
    ;; later stages that are not yet modeled in detail.
    {:request #{{:type :your.context/business-success-achieved}}}
    {:request #{{:type happy-path-complete}}}]))

(defn possible-event-types []
  #{happy-path-complete})

(defn make-bthreads []
  {:your.context.scenarios/happy-path (happy-path)})
```

Generated/review markdown form:

| Scenario | Given | When | Then / trace | Completion event | Evidence | Status | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Happy path |  |  |  | `:checkout.scenarios/foo-complete` |  |  |  |

Guidance:

- Prefer one linear scenario per supported outcome.
- Start with one end-to-end happy-path scenario before adding many alternates.
- Include failure and alternate outcomes as separate bthreads.
- End each model-check scenario with a unique namespaced completion event whose name matches its real scope.
- Link scenario steps to event registry entries.
- Do not let validation, approval, or persistence subflows masquerade as the bounded context's main happy path.

## Rule and safety artifacts

Canonical form should be additive rule/policy bthreads and safety bthreads.

Use rule/policy bthreads when the model should block or redirect behavior without rewriting the positive scenario. Use safety bthreads when model checking should detect a forbidden state or trace by requesting a violation event.

```clojure
(ns your.context.safety
  (:require [tech.thomascothran.pavlov.bthread :as b]))

(defn no-forbidden-transition []
  (b/on :your.context/transitioned
        (fn [{:keys [from to]}]
          (when (= [from to] [:closed :open])
            {:request #{{:type :your.context.safety/status-regression
                         :invariant-violated true}}}))))

(defn make-bthreads []
  {:your.context.safety/no-forbidden-transition
   (no-forbidden-transition)})
```

Generated/review markdown form:

| Property | Forbidden condition or trace | Detection evidence | Negative example/test | Pavlov violation event | Status |
| --- | --- | --- | --- | --- | --- |
|  |  |  |  | `:checkout.safety/invariant-violated` |  |

Safety properties say “bad things never happen.” Sources include validations, guards, authorization checks, assertions, DB constraints, and production incidents.

## Liveness/progress artifacts

Canonical form should be hot-state progress bthreads where appropriate.

Generated/review markdown form:

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
- [ ] Happy-path spine completed for the selected bounded context or scenario family.
- [ ] Event registry namespace/EDN has evidence and statuses.
- [ ] Event registry contains the happy-path spine events before extensive alternate/safety modeling.
- [ ] Event payload schemas exist for modeled events where payloads matter.
- [ ] Scenario bthreads use event registry events.
- [ ] At least one end-to-end happy-path scenario exists for the selected bounded context or scenario family, or any intermediate-only first slice is explicitly labeled as intermediate.
- [ ] Each model-check scenario has a completion event whose name matches its scope.
- [ ] Rule/policy bthreads represent additive blocking/redirecting constraints.
- [ ] Safety bthreads identify violation events.
- [ ] Liveness/progress bthreads identify terminal exceptions.
- [ ] External collaborators have environment/state roles.
- [ ] Model-check config includes relevant bthreads and `:possible` completion events.
- [ ] Markdown catalogs, if present, are generated from or subordinate to executable artifacts.
- [ ] All accepted claims cite ledger IDs.
- [ ] Open questions are explicit.
