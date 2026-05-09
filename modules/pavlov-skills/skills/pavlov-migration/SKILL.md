---
name: pavlov-migration
description: Plan and execute Pavlov-first rewrites of existing non-Pavlov or legacy web/server codebases by extracting requirements, business rules, domain events, scenarios, safety properties, and liveness/progress properties from source code, tests, schemas, API contracts, logs, traces, and human review. Use when modernizing or rewriting an application with Pavlov, recovering requirements from an existing system, coordinating multi-session/multi-agent rewrite discovery, or turning legacy behavior into evidence-backed Pavlov domain models.
---

# Pavlov Migration
The objective of a pavlov migration is to:

1. Identify and specify bounded contexts in an executable model that works with `pavlov-model-checking`
2. Ensure that the *code is the specification* through:
  a) scenarios
  b) anti-scenarios
  c) safety properties
  d) liveness properties
3. Build the application against the model using model-check driven development
4. Guarantee correctness
5. Clearly express business scenarios using behavioral programming and scenario-based programming as David Harel laid out

## Principles to Follow

1. The *code is the specification*. Instead of creating intermediate markdown files, use pavlov code.
2. Keep track of provenance by using metadata on functions or maps so we know where in the legacy code it was from
3. Work within a bounded context
4. Keep side effects out of the business logic using pavlov conventions
5. Specify the core business logic first and specify the domain events before
  - dealing with IO
  - dealing with non-domain events (like fetching or saving an entity)

## Attribution

When writing event specifications or scenarios, use the following metadata attributes to keep track of where it is grounded:

- `:legacy-srcs`: a vector of strings to the legacy src
- `:legacy-tests`: a vector of strings to the legacy src
- `:legacy-docs`: a vector of strings to where it was documented

# Resources
- Identify Bounded Contexts: ./references/bounded-contexts.md
- Workflow to extract requirements from the legacy code:
  + ./references/requirement-extraction.md
- Drawing out bounded contexts: ./references/bounded-contexts.md
- Writing happy path test scenarios: ./references/happy-path-test-scenarios.md
