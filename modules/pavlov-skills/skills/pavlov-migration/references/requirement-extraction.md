## Requirement Extraction Workflow

### Objective and Non-Objectives of the Requirement Extraction Workflow
The goal of the requirements extraction workflow is to get all of the business rules into a single executable, checkable model for the bounded context that *specifically*, *accurately*, and *exhaustively* captures the business rules for that bounded context.

Model check tests are not isolated unit tests. The one test will cover everything for the bounded context. The pavlov skills have more detail on this.

#### Non-goals

- We are not at this point trying to get the production workflow to work. This is all test code
- The tests should fail!
  * Do not add environmental bthreads yet.
  * We are trying to get the tests to express all the requirements. If the tests are passing at this point, where we haven't implemented the production code or environmental bthreads, something is wrong.

### 1. Determine whether preconditions are met.
Before extracting requirements, you must ensure that:

- You are limited to bounded context
- Which is documented at doc/rewrite/<bounded-context-name>/DOMAIN.md
- And has a code map at doc/rewrite/<bounded-context-name>/code-map.json

### 2. Main happy path scenarios

Write the main happy path scenarios. See ./happy-path-test-scenarios.md

IMPORTANT: Happy path scenarios must cover the full lifecycle of a business workflow NOT just part of it. For example, typically a request-response cycle is only part of the full lifecycle. The goal for a scenario is to go from start to finish for the whole business workflow

#### Checklist to evaluate a single scenario

When and after making changes:

- [ ] Ensure the scenario crosses at least one business lifecycle boundary beyond initial intake/creation unless creation is explicitly terminal in this domain.
- [ ] Ensure you have modeled a business flow not a request-response cycle.
- [ ] Ensure you are using business events, not CRUD events.
- [ ] Ensure the scenario/function names are outcome/lifecycle names, not endpoint/action names.
- [ ] Ensure you didn't miss any domain events by checking against the legacy code
- [ ] Ensure the scenario doesn't conflate 2 similar but really different paths.

#### Decide whether enough happy paths have been created
Happy paths do not have to be comprehensive. (There is often combinatorial complexity, and this is handled once safety properties and environment simulation bthreads have been created.)

But there should be enough *representative* happy paths that a new employee could read them and have a very good idea of what happens in the domain.

### 3. Safety Properties
Write the safety properties. See the pavlov model checking skill for more detail on safety properties.

These can be `b/bids`, `b/on`, or more complex forms as needed, including more state-machine-like forms with `b/step`.

IMPORTANT: safety properties at this stage are defined in terms of domain events. They do not apply to CRUD- or API-style events.

#### Checklist for individual safety properties
- [ ] Returns a bid with `:invariant-violated` `true` when the invariant is violated
- [ ] Has a constructor function of the form `make-<details-here>-safety-p`
- [ ] Constructor function has attribution metadata

#### Criteria to Move On
IMPORTANT RULE: Safety properties *must express all* the domain rules about what ought not happen in the code base.

### 5. Identify liveness properties
Identify liveness properties and encode them using pavlov conventions. See the pavlov model check skill for details.

### 6. Iterate
Iterated  as needed until the model for the bounded context is complete.

The REPL can be used to ensure that the test code compiles.

### 7. Define event schemas
Only after the scenarios have settled, define events schemas with malli.
