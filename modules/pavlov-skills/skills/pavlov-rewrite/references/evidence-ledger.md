# Evidence ledger

Use an evidence ledger to make rewrite discovery durable across sessions and agents.

The ledger is append-only unless correcting a factual mistake. Prefer adding a superseding record over silently rewriting prior conclusions.

## Claim states

- `discovered` — observed but not shaped into a model claim.
- `candidate` — plausible event/scenario/property.
- `needs-evidence` — useful but currently unsupported.
- `contradicted` — conflicting evidence exists.
- `accepted` — approved for the Pavlov model.
- `rejected-as-bug` — legacy behavior should not be preserved.
- `deferred` — out of current bounded context or projection.
- `implemented` — rewrite implementation exists.
- `verified` — implementation has passed model/characterization checks.

## Minimal record

```edn
{:id "EV-001"
 :bounded-context :commerce
 :projection :checkout
 :claim-type :safety-property
 :claim "Captured amount must not exceed authorized amount."
 :sources [{:kind :source
            :path "app/services/capture_payment.rb"
            :lines [42 63]
            :excerpt "..."}
           {:kind :test
            :path "spec/payments/capture_spec.rb"
            :name "rejects over-capture"}]
 :confidence :medium
 :status :candidate
 :reviewer nil
 :notes "Needs confirmation for partial captures and multi-currency orders."}
```

## Source kinds

- `:source` — file path, line range, symbol/function/class.
- `:test` — test path and test name.
- `:schema` — table/column/constraint/migration.
- `:api-contract` — OpenAPI/GraphQL/protobuf route/operation.
- `:trace` — trace/span/session/correlation ID.
- `:log` — structured log query or sample.
- `:doc` — product docs, tickets, runbooks.
- `:sme` — human reviewer and date.
- `:inference` — LLM or analyst inference; never enough by itself for `accepted`.

## Confidence guidance

- `:high` — multiple independent sources or executable proof.
- `:medium` — one strong source or several weak sources.
- `:low` — plausible but incomplete evidence.
- `:unknown` — record for follow-up only.

## Rules

- Do not mark a claim `accepted` with only `:inference` evidence.
- Link every Pavlov event/scenario/property to one or more ledger IDs.
- Record contradictions explicitly.
- Record rejected legacy bugs; they explain intentional behavior changes.
- Record privacy redaction decisions for traces or production data.

## Useful derived views

- accepted claims by bounded context
- accepted claims by model-checking projection
- claims without non-inference evidence
- contradictions by model element
- high-risk claims lacking tests
- liveness claims lacking terminal exceptions
- scenarios lacking completion events
