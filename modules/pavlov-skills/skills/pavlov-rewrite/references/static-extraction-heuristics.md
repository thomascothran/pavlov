# Static extraction heuristics

Use static extraction to create high-recall inventories and candidate model artifacts. Static evidence is necessary but usually insufficient by itself for accepted requirements.

## Entry points to domain events

Map external mutation boundaries to candidate commands/events:

- HTTP `POST/PUT/PATCH/DELETE`
- GraphQL mutations
- RPC service methods
- queue/message consumers
- cron/background job handlers
- CLI commands
- webhook handlers
- admin actions

For each entry point, record:

- route/topic/job name
- handler symbol and file
- auth/permission checks
- validations
- state reads/writes
- emitted side effects
- tests covering it

## Persistence to state and invariants

Inspect:

- migrations and schema dumps
- ORM models/entities
- foreign keys, unique constraints, check constraints
- enum/status columns
- triggers/stored procedures
- audit/outbox tables
- soft-delete and tenancy columns

Extract:

- aggregate/entity candidates as supporting state abstractions
- state variables
- legal status values
- uniqueness/ownership invariants
- lifecycle timestamps
- conservation rules for money, inventory, quotas, balances

## Code patterns that imply safety

Search for:

- `validate`, `assert`, `require`, `ensure`, `guard`, `authorize`, `policy`
- exception branches and `return 4xx` branches
- permission checks and tenant scoping
- idempotency keys and duplicate checks
- monetary comparisons and bounds
- state transition guards
- feature flags that alter business behavior
- database transactions and locks

Classify the candidate as:

- data invariant
- authorization invariant
- state-transition invariant
- idempotency invariant
- external-side-effect invariant
- security/privacy invariant

## Tests to scenarios and properties

Map:

- test names to scenario names
- fixtures/factories to initial state
- action under test to event sequence
- assertions to outcomes or safety properties
- negative tests to forbidden behavior
- snapshots to characterization candidates

Beware tests that encode implementation detail. Keep source names in notes, but translate to domain language.

## Side effects to environment modeling

Identify:

- email/SMS/push notifications
- webhooks and third-party API calls
- files/blob storage
- cache writes/invalidations
- ledger/accounting writes
- analytics/audit events
- message publishes

Decide whether each side effect is:

- an event that matters to the domain model
- an environment response to simulate
- a compatibility concern covered by characterization tests only

## Abstraction rules

Keep models tractable:

- replace large IDs with symbolic IDs such as `:order/a`, `:user/b`
- collapse unimportant strings into categories
- bound quantities to representative values: zero, one, max, over-max
- model only statuses relevant to the bounded context or current projection
- represent external systems by result events: success, not-found, denied, timeout, retryable-failure

Record every abstraction decision.
