# State of the art summary

Requirements extraction from arbitrary code is not solved as a fully automatic problem. Current practice is a hybrid of reverse engineering, specification mining, process mining, characterization testing, static analysis, repository RAG, LLM synthesis, and human review.

## Technique families

### Static analysis and code querying

Tools such as CodeQL query code as data. They are strong for finding entry points, guards, data flows, validations, auth checks, state mutations, and repeated patterns.

Use for Pavlov:

- event candidates
- safety candidates
- state mutation boundaries
- bounded-context seams

Limit:

- source structure does not prove business intent.

### API contract extraction

OpenAPI, GraphQL, protobuf, thrift, framework routes, and typed request/response models provide high-confidence interface evidence.

Use for Pavlov:

- command/event vocabulary
- environment stimuli
- legal request sequences

Limit:

- contracts often omit internal business rules.

### Database/schema mining

Schemas, migrations, constraints, triggers, status columns, and audit/outbox tables often reveal domain state machines.

Use for Pavlov:

- aggregate boundaries
- invariants
- status transitions
- state abstractions

Limit:

- many business rules live only in application code.

### Test and characterization mining

Existing tests are often the best executable source of scenarios and assertions. Golden-master/approval tests and differential tests protect behavior during rewrites.

Use for Pavlov:

- positive scenarios
- negative scenarios
- safety checks
- compatibility coverage

Limit:

- tests may be stale, implementation-specific, or encode legacy bugs.

### Dynamic invariant and specification mining

Daikon-style invariant detection and temporal/specification mining infer likely invariants or ordering rules from executions.

Use for Pavlov:

- safety candidates
- ordering constraints
- progress candidates

Limit:

- inferred properties are only as good as trace coverage.

### Process mining

Process mining discovers workflow models, variants, conformance, and bottlenecks from event logs.

Use for Pavlov:

- scenario variants
- liveness/progress obligations
- workflow terminal states

Limit:

- needs meaningful event labels and correlation IDs.

### LLM/agent-assisted repository understanding

Repository-level LLM workflows work best with retrieval, symbol maps, deterministic search, tests, and explicit evidence. They are useful for synthesis but unreliable as sole authorities.

Use for Pavlov:

- cluster evidence into domain concepts
- draft event/scenario/property catalogs
- identify gaps and contradictions
- generate first-pass Pavlov fragments

Limit:

- hallucination and overconfident unsupported claims.

## Verified sources

- Daikon dynamic invariant detector: <https://plse.cs.washington.edu/daikon/>
- CodeQL documentation: <https://codeql.github.com/docs/>
- RESTler stateful REST API fuzzing: <https://github.com/microsoft/restler-fuzzer>
- Process mining overview and tooling: <https://processmining.org/>
- SWE-bench repository-level LLM benchmark: <https://arxiv.org/abs/2310.06770>
- OpenRewrite deterministic refactoring ecosystem: <https://docs.openrewrite.org/>

## Practical conclusion

Use automation to maximize discovery and evidence collection. Use humans, tests, traces, and Pavlov model checking to decide which discoveries become rewrite requirements.
