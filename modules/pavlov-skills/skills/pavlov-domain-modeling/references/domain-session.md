# Domain modeling session guide

Use this guide to run the facilitator interview and translate the answers into the domain modeling files.

## Facilitator prompts

Ask the user the following:

### Scope and boundaries

- What business process or domain slice are we modeling?
- Where does the process start and end?
- Which external actors or systems participate?

Summarize this in the docstring of the rules namespace

### Event catalog

- What events occur in the domain (use the domain’s vocabulary)?
- Which events are environment inputs vs internal outcomes?
- What payload fields matter for rules or decisions?

### Initiating events

- Which event(s) start each scenario?
- Are there alternative starts that should branch at the top?
- Should the starts be modeled as environment inputs or domain-driven outcomes?

Initiating events can be requested in `environment.clj` when they are external inputs. If you need a single top-level start branch, use `make-init-bthread` in `check.clj`. Keep `:bthreads` as a map so branching remains equal-priority.

### Positive scenarios

- List representative successful flows as linear event sequences.
- Identify a unique completion event for each scenario.

These go in the `scenarios.clj` file

### Safety properties

- What must never happen?
- Are there forbidden sequences, invalid states, or illegal combinations?

These go in `rules.clj`.

### Universal properties

- What must eventually happen on every path?
- Are there terminal or resolution events required on all outcomes?

These go in `rules.clj`

### Visualization goals

- Which branches should be clickable at the top of the graph?
- What decisions belong in the init bthread vs deeper environment choices?

## File mapping

- `rules.clj`: domain rules bthreads and any helper functions needed by the rules.
- `environment.clj`: environment inputs and alternative events for branching.
- `scenarios.clj`: positive scenarios and completion events.
- `check.clj`: init bthread (when you want a top-level start branch) and model-check configuration.
- `viz.clj`: navigation, Portal click-through, and graph export.
