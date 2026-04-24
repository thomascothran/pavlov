# Pure Test Workflow

For each iteration:

- [ ] Ensure that the relevant bthread namespaces exist and that they have a `make-bthreads` function
  + At first, `make-bthreads` will return `nil`, and as bthreads are added they return a map of bthread name -> bthread
- [ ] Identify the relevant code and test namespaces in the legacy namespaces
- [ ] Determine what domain events need to exist, and define malli schemas in events namespaces
- [ ] Identify the positive scenarios that need to be implemented. Define them in a scenarios namespace
  + This includes but is not limited to what is under tests in the legacy code and what can be deduced from inspecting the code
- [ ] Identify the safety properties and write them to the safety namespace
  + Again these can include what is asserted in the legacy tests, but should extend to what we can deduce from inspecting the intent of the legacy code
- [ ] Identify what liveness properties and progress requirements are needed, and use bthreads with `:hot` bids to ensure these are verified by the model checker
- [ ] Wire up the following in the check namespace:
  + The bthread that establishes the top-level branching that kick off all scenarios
    * Generally, we try to use one bthread that makes one bid with *all* the events that kick off all scenarios for that feature. (E.g., device lifecycle management)
  + Each test scenario's unique namespaced concluding event type is in the `:possible` set of events
  + There is a `make-bthreads` function that assembles the rules and environment bthreads
- [ ] Iterate against the model checker
  + [ ] Run the model checker to find if all properties are satisfied
    * If yes, move on
    * If no, do the implementation work (in the rules or environment or event namespaces) and re-run the checker
