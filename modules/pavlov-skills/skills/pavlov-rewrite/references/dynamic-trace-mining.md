# Dynamic trace and characterization mining

Use runtime evidence to discover behavior that static analysis and tests miss.

## Useful runtime sources

- OpenTelemetry traces and spans
- structured application logs
- audit tables
- outbox/event tables
- message broker logs
- DB change logs or snapshots/deltas
- API gateway logs
- browser/session recordings
- production incident timelines
- replayable integration tests

## Minimum fields for trace mining

Each event should ideally have:

- timestamp
- event name or operation
- case/correlation ID
- actor/user/tenant when safe
- primary business entity ID
- outcome/status
- relevant payload fields
- source system/component

Without correlation IDs, scenario and liveness mining become unreliable.

## From traces to scenarios

Group events by case ID such as order ID, payment ID, account ID, request ID, or workflow ID.

For each group:

1. sort by causal order or timestamp
2. normalize low-level operations into candidate domain events
3. identify common paths and variants
4. mark terminal outcomes
5. record rare/error paths separately
6. link each scenario to trace samples

## From traces to liveness

Look for recurring eventual relationships:

- submitted order eventually ships, cancels, refunds, or expires
- payment authorization eventually captures, voids, or fails
- queued job eventually succeeds, fails, retries, or dead-letters
- verification request eventually verifies or expires
- subscription renewal eventually invoices, charges, retries, or cancels

Always capture:

- trigger event
- required eventual outcome
- allowed terminal exceptions
- time/window semantics
- observed violations or timeouts

## From values to invariants

Dynamic invariant tools and simple aggregation can propose safety candidates:

- non-null fields after a transition
- numeric bounds
- amount relationships
- sorted/monotonic timestamps
- one-active-record-per-owner
- status never returns to prior state
- child rows imply parent status

Mark these as likely invariants, not facts. They need review and negative examples.

## Characterization tests

Use characterization tests to protect behavior that is externally important but not yet modeled.

Capture:

- input request/message/job trigger
- normalized response/output
- DB deltas or selected side effects
- emitted messages/webhooks/emails
- stable fixture state

Normalize nondeterminism:

- timestamps
- UUIDs
- random values
- ordering of async outputs
- environment-specific URLs/secrets
- third-party response IDs

## Failure modes

- missing correlation IDs
- logs with implementation names but no domain meaning
- traces that cover only happy paths
- production bugs preserved as requirements
- PII/secrets in captured data
- clock skew and async reordering
- retries and duplicate delivery mistaken for distinct business events
- overfitted invariants from too little data

## Output

Produce:

- trace source summary
- event normalization map
- scenario candidates with example trace IDs
- liveness candidates with observed timing
- invariant candidates with support counts
- data quality gaps
