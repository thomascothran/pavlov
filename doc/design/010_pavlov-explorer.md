# Pavlov Explorer

Status: **signed off** (2026-11-11). First version implemented in `modules/pavlov-explorer`.

This document says what the explorer does and why. It does not say how it is
built. UI layout is decided separately, from clickable candidates.

## Why

A behavioral program is a set of small, independent rules. What the program
does as a whole only shows up when the rules run together, and there are often
several things that can happen next. That makes it hard for anyone who does not
read the code to answer "what can this program do?" and "how did it end up
here?".

Today a program can be walked through Clojure's `nav` in a tool such as Portal.
That works for a programmer at a REPL. It is not something a product manager or
an end user can use, and it does not look like something they would want to.

The explorer lets a non-programmer walk a program one event at a time, see the
choices at each point, and see how they got where they are.

## Who it is for

- **Explorer user**: a product manager or end user. Does not read Clojure and
  does not use a REPL. Opens a link in a browser.
- **Program author**: a developer. Decides which programs are available to
  explore and runs the explorer.

## Terms

| Term | Meaning |
|------|---------|
| Program | A set of bthreads offered for exploration, with a name and a description. |
| Catalogue | The list of programs the explorer offers. |
| Event | Something that happens in the program. Selecting one moves the program on. |
| Position | Where the user is in a program: the start, or the point reached after a sequence of events. |
| Choices | The events that can happen next from a position. |
| Trace | The sequence of events from the start to the current position. |
| Sibling | Another choice that was available at the same position as an event in the trace. |
| Role | What a bthread is for: a rule of the program, the environment (things done from outside), or a safety check. |

## Decisions already made

| Question | Decision |
|----------|----------|
| How does a program get into the explorer? | The program author supplies a catalogue. The user picks a program from it. |
| What is a catalogue entry? | A name, a description, and the same configuration the model checker takes. |
| What does the explorer know about a program? | Exactly what the model checker knows. Both work from the same description of the program's states and transitions, so the explorer never shows a step the checker would not consider, and never hides one. |
| How are events and bthreads worded? | As written in the code. No renaming or tidying. |
| Which checker findings are shown? | Only what is true of the position the user is at: dead end, proper ending, rule violation. |
| What if the program is too big to explore fully? | The explorer stops at the limit and says so. It does not explore further on request. |
| Can a position be shared? | Yes. The page address identifies the program and the trace. |
| What happens to later steps when I go back? | They disappear at once. The browser's forward button brings them back. |
| Am I told when I return to a situation I have been in? | Yes. The position is marked and points at where in my trace I was there before. |
| What is a dead end called when the program does not check for them? | "Nothing more can happen here", not "stuck". |
| How much of the program's shape is on screen? | The local view only: the trace, with the alternatives at each step, and the choices at the current position (candidate A, "Trail"). A whole-program map may be added later. |
| When does the explorer notice that a program's code changed? | When someone asks. A program is explored the first time it is opened and kept, so positions stay put while people are using it. A reload control on the page rebuilds it from the current code. |
| How does the explorer find a bthread's code? | The program author may name, per bthread, the definition whose code to show. Bthreads without one are not offered code. |
| Look and feel | The cyberpunk theme used by the existing Pavlov web examples. |

## Constraints on programs

- Exploring a program means its bthreads are stepped many times, along many
  routes, and revisited. Bthreads in the catalogue must therefore not do
  anything to the outside world. This is the same rule that applies to model
  checking.
- A program is explored up to the limit set in its configuration. Beyond that
  limit nothing is known. A configuration that sets no limit is explored up
  to 5,000 states, so that a program that never ends can still be opened.

## User stories and behavior

### 1. Choose a program

> In order to explore the program I care about
> As an explorer user
> I want to pick it from a list of the programs on offer

```gherkin
Feature: Choose a program

  Scenario: See what is on offer
    Given the catalogue contains "Account opening" and "Loan approval"
    When I open the explorer
    Then I see "Account opening" and "Loan approval"
    And I see the description of each

  Scenario: Start exploring a program
    Given I am looking at the catalogue
    When I choose "Account opening"
    Then I am at the start of "Account opening"
    And my trace is empty
    And I see the choices available at the start

  Scenario: Switch to another program
    Given I am part-way through "Account opening"
    When I return to the catalogue and choose "Loan approval"
    Then I am at the start of "Loan approval"

  Scenario: The catalogue is empty
    Given the catalogue contains no programs
    When I open the explorer
    Then I am told there are no programs to explore
```

### 2. See what can happen next and choose

> In order to understand what the program can do
> As an explorer user
> I want to see every event that can happen next
> And I want to pick one and move on

Choices are the reason the tool exists: they are where the program's behavior
forks. Every choice must be shown, and each must be distinguishable from the
others, including two events of the same type that carry different data.

```gherkin
Feature: Move forward through a program

  Scenario: See the choices
    Given I am at a position where ":application-submitted" and ":initial-deposit-paid" can happen next
    Then I see both as choices
    And each is shown as it is written in the code

  Scenario: Choose the next event
    Given ":application-submitted" is one of my choices
    When I choose ":application-submitted"
    Then ":application-submitted" is added to the end of my trace
    And I see the choices available after it

  Scenario: Only one thing can happen next
    Given exactly one event can happen next
    Then I see that single event as my only choice
    And I am not moved forward until I choose it

  Scenario: Choices of the same type with different data
    Given two events of type ":payment-received" with different amounts can happen next
    Then I see two separate choices
    And I can tell them apart by their data

  Scenario: See the data an event carries
    Given a choice is an event that carries data
    When I ask for the details of that choice
    Then I see the whole event as it is written in the code
```

### 3. See how I got here

> In order to understand why the program is in its current situation
> As an explorer user
> I want to see the events that led to my current position, in order

```gherkin
Feature: See the trace

  Scenario: The trace lists every event so far
    Given I chose ":application-submitted" and then ":request-cip-verification"
    Then my trace shows ":application-submitted" followed by ":request-cip-verification"
    And the last one is marked as my current position

  Scenario: At the start
    Given I have just chosen a program
    Then my trace shows that I am at the start
    And no events are listed

  Scenario: A long trace
    Given my trace is longer than fits on screen
    Then my current position stays visible
    And I can still reach every earlier event in the trace
```

### 4. Go back and take a different route

> In order to compare what happens under different choices
> As an explorer user
> I want to return to an earlier point in my trace
> And choose differently from there

```gherkin
Feature: Go back

  Scenario: Return to an earlier point
    Given my trace is ":a", ":b", ":c"
    When I go back to ":a"
    Then my position is the one reached after ":a"
    And my trace is ":a"
    And ":b" and ":c" are no longer shown
    And I see the choices that were available after ":a"

  Scenario: Take a different route after going back
    Given my trace is ":a", ":b", ":c"
    And ":x" was also a choice after ":a"
    When I go back to ":a"
    And I choose ":x"
    Then my trace is ":a", ":x"

  Scenario: Return to the start
    Given my trace is ":a", ":b"
    When I go back to the start
    Then my trace is empty
    And I see the choices available at the start

  Scenario: Step back one event
    Given my trace is ":a", ":b"
    When I step back once
    Then my trace is ":a"
```

### 5. Switch to a sibling

> In order to see "what if the other thing had happened instead"
> As an explorer user
> I want to swap the event I just chose for one of its siblings
> Without first going back

```gherkin
Feature: Switch to a sibling

  Scenario: See the siblings of my last event
    Given my trace is ":a", ":b"
    And ":x" and ":y" were also choices after ":a"
    Then I can see that ":x" and ":y" were the alternatives to ":b"

  Scenario: Switch to a sibling
    Given my trace is ":a", ":b"
    And ":x" was also a choice after ":a"
    When I switch ":b" for its sibling ":x"
    Then my trace is ":a", ":x"
    And I see the choices available after ":x"

  Scenario: An event with no siblings
    Given my trace is ":a", ":b"
    And ":b" was the only choice after ":a"
    Then I am shown that ":b" had no alternatives

  Scenario: Switch a sibling earlier in the trace
    Given my trace is ":a", ":b", ":c"
    And ":x" was also a choice after ":a"
    When I switch ":b" for its sibling ":x"
    Then my trace is ":a", ":x"
```

### 6. Know what kind of position I am at

> In order to tell a normal step from an ending or a problem
> As an explorer user
> I want the explorer to tell me, in plain words, when I have reached a dead
> end, a proper ending, or a rule violation

These are stated only for the position the user is at. The explorer does not
warn about them in advance and does not list them for the whole program.

```gherkin
Feature: Mark the position I have reached

  Scenario: A proper ending
    Given my next choice is an event that ends the program
    When I choose it
    Then I am told the program has finished
    And there are no further choices

  Scenario: A dead end
    Given I am at a position where nothing can happen next
    And the program has not finished
    Then I am told the program is stuck here
    And there are no further choices

  Scenario: A dead end in a program that does not check for them
    Given the program's configuration turns deadlock checking off
    And I am at a position where nothing can happen next
    Then I am told nothing more can happen here
    And I am not told the program is stuck

  Scenario: A situation I have been in before
    Given my trace is ":a", ":b", ":c"
    And the program is in the same situation after ":c" as it was after ":a"
    Then I am told I have been here before
    And I am told it was after ":a"
    And I can go back to that point

  Scenario: A rule violation
    Given my next choice is an event that a safety check reports as a violation
    When I choose it
    Then I am told a rule has been violated
    And I am told which safety check reported it

  Scenario: The edge of what was explored
    Given the program was only explored up to its limit
    And I am at a position whose next steps were not explored
    Then I am told the program was only explored this far
    And I am told nothing is known beyond this point
    And I am not told the program is stuck

  Scenario: Leaving a marked position
    Given I am at a dead end
    When I step back once
    Then I see the choices that were available before it
```

### 7. See what each bthread is doing

> In order to understand why these are my choices
> As an explorer user
> I want to see, for each bthread, what it is asking for, waiting for and
> preventing at my current position
> But only when I ask, because there may be a lot of them

```gherkin
Feature: Inspect bthreads

  Scenario: Hidden until asked for
    Given I am exploring a program
    Then bthread details are not shown

  Scenario: Open the bthread view
    When I open the bthread view
    Then I see every bthread in the program by name
    And I see each one's role: rule, environment, or safety check

  Scenario: See what a bthread is doing
    Given the bthread view is open
    When I open a bthread
    Then I see which events it is requesting
    And I see which events it is waiting for
    And I see which events it is blocking
    And I see its internal state as it is written in the code

  Scenario: The view follows me
    Given the bthread view is open
    When I choose an event
    Then the bthread view shows the bthreads as they are at my new position
    And the view stays open

  Scenario: See which bthreads an event affected
    Given the bthread view is open
    When I choose an event
    Then I can tell which bthreads changed because of it

  Scenario: A bthread that has finished
    Given a bthread has nothing further to do
    Then it is shown as finished

  Scenario: Close the bthread view
    Given the bthread view is open
    When I close it
    Then bthread details are not shown
    And my position is unchanged
```

### 8. See a bthread's code (optional)

> In order to check a rule against what I was told it does
> As an explorer user
> I want to open a bthread and read its code

Optional for the first version. It is included if it can be done without
asking program authors to change how they write bthreads; otherwise it waits.

```gherkin
Feature: See a bthread's code

  Scenario: Code is available
    Given the bthread view is open
    And the code for ":block-deposit-until-cip-verified" is available
    When I ask to see its code
    Then I see the code that defines it

  Scenario: Code is not available
    Given the code for a bthread is not available
    Then I am not offered its code
    And everything else about the bthread is still shown
```

### 9. Share a position

> In order to show someone else what I found
> As an explorer user
> I want to send them a link that opens the explorer at my position

```gherkin
Feature: Share a position

  Scenario: A link opens the same position
    Given I am in "Account opening" with trace ":a", ":b"
    When someone else opens the address of my page
    Then they are in "Account opening" with trace ":a", ":b"
    And they see the same choices I see

  Scenario: Reloading keeps my place
    Given I am part-way through a program
    When I reload the page
    Then I am at the same position with the same trace

  Scenario: Browser back and forward
    Given I chose ":a" and then ":b"
    When I use the browser's back button
    Then my trace is ":a"
    When I use the browser's forward button
    Then my trace is ":a", ":b"

  Scenario: Two people explore independently
    Given two people have the same program open
    When one of them chooses an event
    Then the other's position does not change

  Scenario: A link to a program that is no longer offered
    Given a link names a program that is not in the catalogue
    When I open the link
    Then I am told that program is not available
    And I am shown the catalogue

  Scenario: A link whose trace no longer fits the program
    Given a link's trace includes a step the program can no longer take
    When I open the link
    Then I am told the link no longer matches the program
    And I am placed at the last step of the trace that is still possible
```

### 10. Offer programs for exploration

> In order to let people explore my programs without my help
> As a program author
> I want to list the programs to offer and start the explorer
> And reuse the configuration I already wrote for model checking

```gherkin
Feature: Offer programs

  Scenario: Offer a program I already model check
    Given I have a model-check configuration for "Account opening"
    When I add it to the catalogue with a name and a description
    And I start the explorer
    Then "Account opening" can be chosen and explored

  Scenario: Roles come from the configuration
    Given a configuration lists its bthreads, environment bthreads and safety bthreads separately
    When a user opens the bthread view for that program
    Then each bthread is shown with the role the configuration gave it

  Scenario: The exploration limit comes from the configuration
    Given a configuration sets a limit on how much of the program is explored
    When a user explores that program
    Then nothing beyond that limit is offered

  Scenario: Pick up a change to a program
    Given "Account opening" has been explored
    And I have changed its code
    When the program is reloaded from its page
    Then the explorer shows the program as it is now
    And the user stays at the same position if the program can still reach it

  Scenario: A program whose code is broken
    Given a program cannot be built from its current code
    When a user opens it
    Then they are told the program could not be loaded
    And they can reload it once the code is fixed

  Scenario: The explorer and the model checker agree
    Given a program is in the catalogue
    Then every trace a user can follow in the explorer is one the model checker considers
    And every trace the model checker considers within the limit can be followed in the explorer
```

## Not in the first version

- Blocking an event at a chosen point to see what the program does without it.
- Generating scenario or test code from a trace.
- Warning that a choice leads to an ending, dead end or violation before it is
  chosen.
- A list of everything the model checker found, with a jump to each finding.
- Showing unmet obligations (hot states) and endless loops (livelocks).
- Friendly names or descriptions for events and bthreads.
- Exploring past the configured limit on request.
- Explaining why an event is *not* among the choices (which bthread blocks it).
- Editing bthreads or programs from the explorer.
- Two people sharing one live session.
- A whole-program map.
- Picking up code changes without being asked.
