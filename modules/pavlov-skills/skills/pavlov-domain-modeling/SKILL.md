---
name: pavlov-domain-modeling
description: Facilitate iterative domain modeling with Pavlov bthreads. Use only when requested
---

# Pavlov domain modeling

Use this skill to guide a domain expert through a structured modeling session and emit the bthread scaffolding as Clojure files. The outcome will be the domain model captured in Clojure source code and written using the pavlov library.

## Workflow (iterative).

1. Facilitate a domain interview to capture the event catalog, initiating events, positive scenarios, safety properties, and universal properties.
2. Clarify which events are environment inputs vs domain-driven outcomes, including payload shapes and terminal/completion events.
3. Draft bthreads, review with the domain expert, and iterate on the model until the scenarios and properties feel right.
4. Generate the Clojure deliverables and update them as the domain knowledge evolves.
5. Provide visualization helpers so stakeholders can explore branches via nav or Portal.

## Deliverables

The deliverables are source code, by default:

- `rules.clj`: domain rules bthreads; define `make-bthreads` returning a map of bthread name -> bthread.
  + Universal liveness properties will be defined here as well.
- `environment.clj`: simulated environment inputs; define `make-bthreads` returning a map;
  + RULE: keep all *initiating events* (typically bthreads that make a `:request` in their first bid instead of a `:wait-on`) in *one* bthread that returns *one* bid where the requests are a set. Call this bthread `::init-events` (namespaced).
- `safety.clj`: safety bthreads
- `scenarios.clj`: positive scenarios; define `make-bthreads` returning a map; each scenario ends with a unique completion event. Define existential liveness properties for *each* completion event
- `check.clj`: model-check setup; assemble `:bthreads`, `:safety-bthreads`, and `:liveness` (keep `:bthreads` as a map when start events come from environment bthreads).
  + `:liveness` will need to include all the liveness definitions from the rules and scenarios
- `viz.clj`: visualization helpers; define functions for nav exploration, Portal click-through, and graph export.

The user may specify a directory or suggest alternative names.

## Guidelines

ALWAYS evaluate clojure source code to verify your understanding.

This will be primarily done with the model checker, but secondarily, you can use:

- tech.thomascothran.pavlov.graph
- tech.thomascothran.pavlov.nav
- tech.thomascothran.pavlov.search

DO NOT JUST READ CODE AND SAY WHAT YOU *THINK* IT DOES, EXECUTE AND **VERIFY** IT AT THE REPL

## Modeling rules

- Keep environment inputs isolated in `environment.clj`; avoid mixing them with domain rules.
- Keep scenarios linear (`b/bids` with `:wait-on`) and let branching happen in the init/environment layers.
- Use namespaced completion events for scenarios so existential liveness properties stay explicit.
- Put invariants in `:safety-bthreads` and progress requirements in `:liveness`.
- Use one `check/check` call for the whole model. Define existential liveness checks for each scenario.

## References

- Use `references/domain-session.md` for facilitation prompts and file templates.
- Use `references/bthread-templates.md` for example templates of the clojure files to output
- Use the model-checking guidance, refer to the `pavlov-model-checking` skill and the docstring for
  - The `tech.thomascothran.pavlov.model.check` namespace, and
  - The `tech.thomascothran.pavlov.model.check/check` function
- For a blog-style tutorial, read references/bank-domain-example.md.
