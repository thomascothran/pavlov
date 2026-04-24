---
name: pavlov-domain-modeling
description: Facilitate iterative domain modeling with Pavlov bthreads. Use only when requested
---

# Pavlov domain modeling

Use this skill to guide a domain expert through a structured modeling session and emit the bthread scaffolding as Clojure files. The outcome will be the domain model captured in Clojure source code and written using the pavlov library.

## Guidelines

ALWAYS evaluate clojure source code to verify your understanding.

This will be primarily done with the model checker, but secondarily, you can use:

- tech.thomascothran.pavlov.graph
- tech.thomascothran.pavlov.nav
- tech.thomascothran.pavlov.search

DO NOT JUST READ CODE AND SAY WHAT YOU *THINK* IT DOES, EXECUTE AND **VERIFY** IT AT THE REPL

## Modeling rules

- Keep the following things in separate namespaces:
  + Business rules and processes
  + Side-effecting bthreads, such as database interactions or HTTP calls
    * Keep the bthreads that perform side effects separate from the bthreads that simulate those side effects.
  + Safety property bthreads
    * Safety properties can also live in other bthreads (including production bthreads) when that keeps the invariant close to the relevant behavior.
  + Progress requirements
    * To assert that a bthread *must* progress, a bthread can return a `:hot` bid.
  + Test code
- Model business rules as linear scenarios with `b/bids` where possible.
  + Avoid branching inside a scenario bthread. Prefer multiple scenario bthreads per feature.
  + Use `:block` to add constraints without rewriting an existing scenario.
- In test scenarios, use namespaced completion events.
- Model checks are not like traditional unit tests that test one scenario. They test a whole slice of the program, or even a whole feature.
  + Prefer fewer model-check tests, ideally one per feature.
- Pass a set of `:possible` event types to ensure the model checker verifies that event is possible.
  + This should be used with the concluding, namespaced, unique event that ends *every* test scenario, and can be asserted quite broadly of almost all event types.

## Choose references

- Use `references/business-scenarios.md` when writing the main scenario namespace for a feature.
- Use `references/safety-and-policy-bthreads.md` when adding safety invariants or additive blocking rules.
- Use `references/state-and-environment-bthreads.md` when modeling external systems, user actions, or stateful test doubles.
- Use `references/check-clj-template.md` when assembling the model checker config
- Use `references/viz-template.md` when building a navigation or graph visualization namespace.

## Related guidance

- Use the model-checking guidance, refer to the `pavlov-model-checking` skill and the docstring for
  - The `tech.thomascothran.pavlov.model.check` namespace, and
  - The `tech.thomascothran.pavlov.model.check/check` function
