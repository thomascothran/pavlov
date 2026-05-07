# Happy Path Test Scenarios

Happy path scenarios exist for the purpose of

- declaring *end to end* sequences of events that go *completely* to the end of a business process.
- documenting the happy path in a readable intuitive way
  + Hence all the events necessary to complete the business process should be present and expressed in business languages
- ensuring that comprehensive sequence of events for representative happy paths using pavlov's model checker
  + By comprehensive here I mean *all* the events required to move through the happy path for that business process within the bounded context

The point is to be able to assert (using pavlov's model checker) that the path exists and that the business process can be completed.

## IMPORTANT RULES

When creating happy path scenarios, follow these rules:

- DO stay within a bounded context
  + DO NOT proceed without having a documented DOMAIN.md
  + DO NOT model events outside the bounded context
    * Keep in mind that one domain can subscribe and block events from another domain - keeping them separate but with the ability for them to compose.
- DO consider the business process you are implementing in full in the legacy code base before creating the happy path.
  + DO NOT only consider the chain of code called during a request response cycle, or during handling a single message.
- DO use domain events that use business language
  + DO NOT use API or CRUD language
  + Examples:
    * BAD: update-cart - updates are not domain events, they are CRUD events
    * BAD: POST-update - transport specific
    * BAD: handle-kafka-event - transport specific
    * BAD: submit-form
    * BAD: persist-record
    * BAD: create-record
    * GOOD: add-item-to-cart
  + Criteria: would the event make sense still make business sense if the UI, API route, database table, or framework were replaced. Would a non-technical user recognize the business process and business terms?
- DO model business processes from *end to end*
  + DO NOT: model only part of the business process, such as the part that occurs in an http request/response cycle or a screen in the UI.
  + Consider: End to end is determined by the bounded context. What are the terminal or final events within the bounded context.
  + BAD: path has events A, B, and C because these are the events that occur during a request/response cycle
    * End to end does not mean request response - it refers to the *business process* which may consist of many requests over an extended period of time.
  + GOOD: the happy path has A, B, C, D, and E, where E is a *final* event for the business process.
  + TEST: in the business process, can something happen after the events in the business process?
- DO use `:wait-on` to wait for events
  + DO NOT: `:request` the events in the business process.
  + But note: the final event, which is a namespaced event signifying the end of that path, will be requested.
  + Context: something else will request the events, either a test environment bthread that simulates events, or a domain bthread (a production bthread) that implements the business process. That's not part of constructing the happy path.
- DO make events *concrete*, *specific* and *accurate*
  + DO NOT: use generic events like `:task/work-complete`
    * What is the "work" that is complete?
    * This should be something like: `:task/inspection-completed`
- DO namespace events, beginning with the name of the bounded context
- DO use constructor functions for each individual scenario
  + E.g., `make-full-order-and-delivery-path-bthread`
  + Use the form `make-<specifics-go-here>-bthread`
- DO model scenarios linearly
  + use `b/bids`
  + DO NOT build happy path state machines
  + DO NOT try to handle branching scenarios in a single bthread
- DO separate similar but not quite the same workflows
  + DO NOT try to build a generic abstraction of a happy path
  + If events are not identical between two very similar business processes, model their happy paths separately
  + Tip: if there are entities (e.g., tasks in a task manager) whose types differ and whose business logic differs slightly, these need to be separated. Watch out for types and subtypes
+ DO add attribution metadata on the constructor function for a happy path.
+ DO put the happy path scenarios in test namespaces

Note that these rules apply specifically when constructing happy paths. Many of these (e.g., requesting events or having http related events) may happen in a different phase of development.
