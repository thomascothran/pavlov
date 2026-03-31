# Phase A: Web Model-Checking Problem Statement

Status: Draft
Date: 2026-03-07

## Context

Pavlov can currently model-check behavioral programs so long as the checked model is assembled and explored on one JVM. This works well for domain logic, safety bthreads, liveness properties, and simulated external input via environment bthreads.

Pavlov's runtime already spans Clojure and ClojureScript, and long-running programs can accept external events and notify subscribers. However, the repository does not yet define a first-class web architecture that lets browser behavior, server behavior, and environment behavior be designed together as one model and then realized across browser and server runtimes.

For web applications, we want to preserve Pavlov's model-first workflow. The goal is to design the application all the way through the user-facing contract, not just the server-side domain rules.

## Current Repository Baseline

- `modules/pavlov-devtools/src/tech/thomascothran/pavlov/model/check.clj` builds one LTS from one assembled model containing `:bthreads`, `:safety-bthreads`, and `:environment-bthreads`.
- `modules/pavlov-devtools/src/tech/thomascothran/pavlov/search.cljc` explores that model by saving and restoring per-bthread state snapshots.
- `modules/pavlov/src/tech/thomascothran/pavlov/bprogram/ephemeral.cljc` already supports external events via `submit-event!`, subscribers via `subscribe!`, and pluggable publishers.
- `README.md` positions Pavlov as a Clojure/ClojureScript behavioral programming library and treats external events as things like UI actions or HTTP requests.

## Problem

We need a web-oriented architecture for Pavlov that allows browser-facing behavior and server-side behavior to be expressed with a shared event language, so that the whole application can be designed and checked as one JVM-side model before committing to rendering or transport details.

The checked boundary should reach user-visible UI behavior, but at the level of semantic events and commands rather than exact DOM mutations. For example, the model should be able to express outcomes such as "field X enters warning state" or "form Y is disabled while request Z is in flight" without tying correctness to a specific DOM reconciler or rendering library.

## Why The Current Situation Is Insufficient

- The current checker assumes one assembled, restorable, in-memory model on the JVM.
- Environment bthreads can simulate outside inputs, but there is not yet a standard way to represent browser and server roles, network behavior, or UI adapters inside one coherent web design.
- There is no current component or rendering abstraction for web UIs that composes naturally with bthreads and events.
- There is no agreed model for how browser interactivity, server coordination, and side-effecting adapters work together without collapsing into a heavy SPA architecture.

## Constraints And Requirements

### Hard constraints

- Reuse the current Pavlov model-checking approach as much as possible rather than requiring a separate formalism or a full checker rewrite.
- Allow browser, server, and environment behavior to be assembled into one JVM-checkable model.
- Keep a shared event schema across CLJ and CLJS.
- Represent DOM, HTTP, storage, and transport effects as commands or events at the checked boundary, with adapters performing the actual effects.
- Do not assume a React-style architecture in which the frontend owns the application logic and the backend is reduced to persistence.

### Strong preferences

- Default toward server-driven SPA or hypermedia-style applications in the spirit of Datastar, while remaining stack-agnostic.
- Keep the browser thin by default.
- Allow optional browser bthreads when richer local interactivity is useful.
- Remain SSR-friendly.

### Real-world concerns that must be accounted for

- per-session event ordering
- reconnect
- retries and duplicate delivery
- latency and timeouts
- multi-tab behavior
- multi-user behavior

## Scope Of The Checked Contract

In scope for checking:

- user-visible interaction outcomes expressed as semantic events or commands
- browser, server, and environment behavior modeled together
- browser-side behavioral logic when needed, so long as it can participate in the shared checked model

Out of scope for checking, at least initially:

- exact DOM structure
- exact DOM reconciliation semantics
- commitment to a specific rendering library, transport, or web framework
- a final definition of the component model

## Core Tensions

The design should support server-first, server-driven web applications, but browser behavior must not become second-class. Some applications will need browser-side bthreads for responsiveness while requests are in flight or while local interactions are being coordinated. The architecture therefore has to support optional distribution of behavior without forcing a heavy-client default.

The most important thing is to design an open system that can be used in multiple ways.

Authority is also not trivial. Browser and server cannot both independently own the same synchronization point. The design must leave room for authority to be determined by the behavioral rules of the application rather than assuming a universal "server always wins" or "browser always wins" policy.

## Problem Statement

How do we extend Pavlov with a web application architecture such that:

1. a whole web application can be designed behaviorally and checked as one JVM-side model,
2. the checked contract reaches user-visible UI behavior through semantic events and commands,
3. production behavior can be realized across server and browser runtimes with a shared event language,
4. the default architecture stays server-driven and thin-client-friendly, and
5. optional browser-side behavior can be introduced without abandoning end-to-end model-driven design?

## Design constraints

Across the options below, the most important idea is to keep the checked boundary semantic.
UI bthreads should be able to declare things like:

- a field entering a warning state
- a form becoming disabled while a request is in flight
- a component entering a loading, error, or ready state
- a semantic request being issued
- a semantic user intent being observed

The actual DOM writes, HTML rendering, network IO, storage IO, and frontend library integration should be handled by adapters or IO bthreads below that semantic boundary.

## Example Usage

Suppose we model the interaction in terms of semantic user intents, domain commands and events, durable UI projections, and transient UI effects.

The important point is that these are not DOM mutations or renderer-specific lifecycle callbacks. They are semantic events and outputs that could be realized by different adapters.


```clojure
;; The user arrives at the create-task screen.
{:type :ui.intent/view-opened
 :view/id :create-task}

;; The UI is projected into a durable semantic state.
{:type :ui.projection/form-state
 :form/id :create-task
 :title "Create Task"
 :status :editing
 :fields {:task-name {:label "Name"
                      :kind :text
                      :value ""
                      :required true}
          :task-type {:label "Type"
                      :kind :select
                      :value nil
                      :required true
                      :options [{:label "Inside" :value "inside"}
                                {:label "Outside" :value "outside"}]}}
 :actions {:submit {:label "Create task"
                    :enabled true}}}

;; The user submits a value.
{:type :ui.intent/form-submitted
 :form/id :create-task
 :values {:task-type "inside"
          :task-name "take out"}}

;; The UI may immediately move into a durable "submitting" state.
{:type :ui.projection/form-state
 :form/id :create-task
 :title "Create Task"
 :status :submitting
 :fields {:task-name {:value "take out"}
          :task-type {:value "inside"}}
 :actions {:submit {:enabled false}}}

;; A semantic command is issued.
{:type :task.command/create
 :request/id #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
 :task/name "take out"
 :task/type "inside"}

;; The domain rejects the command.
{:type :task.event/create-rejected
 :request/id #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
 :reason :duplicate-name
 :field-errors {:task-name "A task by that name already exists"}}

;; The UI is projected back into an editable state with semantic errors.
{:type :ui.projection/form-state
 :form/id :create-task
 :title "Create Task"
 :status :editing
 :fields {:task-name {:value "take out"
                      :error "A task by that name already exists"}
          :task-type {:value "inside"}}
 :actions {:submit {:enabled true}}}

;; The user corrects the name and submits again.
{:type :ui.intent/form-submitted
 :form/id :create-task
 :values {:task-type "inside"
          :task-name "take out trash"}}

{:type :task.command/create
 :request/id #uuid "ffffffff-1111-2222-3333-444444444444"
 :task/name "take out trash"
 :task/type "inside"}

{:type :task.event/created
 :request/id #uuid "ffffffff-1111-2222-3333-444444444444"
 :task/id 1234
 :task/name "take out trash"
 :task/type "inside"}

;; Durable UI state can change.
{:type :ui.projection/view-state
 :view/id :create-task
 :status :complete}

;; Transient UI effects can also be emitted.
{:type :ui.effect/navigate
 :to {:view/id :task-detail
      :task/id 1234}}

;; The next screen can be projected semantically as well.
{:type :ui.projection/detail-view
 :view/id :task-detail
 :entity/id 1234
 :fields {:task-name "take out trash"
          :task-type "inside"}}

```

There are several things intentionally left open here:

- whether one bprogram runs in production or multiple cooperating bprograms run
- whether authority for a given concern lives on the server, in the browser, or is shared through an explicit protocol
- whether the server is long-lived or reconstructed per request
- how these semantic projections and effects are realized in HTML, Datastar, DOM operations, or another UI adapter

The important thing is that the semantic contract is stable even if the realization strategy changes.

### Promising Hints of What We Could Do

But there are a few promising hints here about what this could let us do:

- We can separate semantic UI behavior from UI realization.
  + A Datastar adapter could lower `:ui.projection/*` and `:ui.effect/*` into HTML patches, signal updates, SSE messages, or request/response flows.
  + A frontend behavioral runtime could lower the same semantic outputs into DOM operations, local component state, or other frontend effects.
- We can separate durable UI state from transient UI effects.
  + Durable state includes things like field values, validation messages, loading, ready, or error states, enabled actions, visible rows, or current filters.
  + Transient effects include things like navigation, focus, scroll-into-view, toast messages, opening a menu, or starting an animation.
  + This distinction matters for replay, reconnect, dedupe, and SSR.
- We can keep domain behavior and UI behavior related without collapsing them into one layer.
  + Domain commands and events can remain semantic and renderer-independent.
  + UI behavior can react to those same semantic events without needing to know how persistence, transport, or rendering is implemented.
- We can keep open the option of thin clients by default while still allowing browser-side bthreads where they help.
  + A thin-client architecture might keep most authority on the server and use browser code mainly as an adapter.
  + A richer client architecture might let browser bthreads own highly local interaction concerns while still participating in the same shared semantic event language.
- We can model the whole interaction in one checked model even if the production realization is split across browser and server.
  + Browser behavior, server behavior, and environment behavior could all be assembled together during model checking.
  + Production-specific IO bthreads could be swapped out for non-side-effecting model-checking equivalents.
- We can make authority explicit instead of accidental.
  + Some concerns may be server-authoritative, such as durable committed data.
  + Some concerns may be browser-authoritative, such as temporary focus or in-progress cursor movement.
  + Some concerns may require an explicit session protocol, such as retries, duplicate suppression, reconnect, and multi-tab coordination.
- We can make the same semantic model realizable in more than one frontend style.
  + That creates options for Datastar-style server-driven applications.
  + It also creates options for frontend bthreads with DOM IO bthreads.
  + Ideally, changing between those approaches should mostly require adapter changes rather than rewrites to the semantic model.

## Example: Datatable Or Spreadsheet-Style Editing

A more demanding example is a table where users can sort, filter, edit cells inline, and encounter conflicts from concurrent updates.

The same separation still helps:

```clojure
;; User opens an inventory table.
{:type :ui.intent/view-opened
 :view/id :inventory-grid}

{:type :ui.projection/grid-state
 :view/id :inventory-grid
 :status :ready
 :columns [{:column/id :sku :label "SKU"}
           {:column/id :name :label "Name"}
           {:column/id :quantity :label "Quantity"}]
 :rows [{:row/id 101
         :cells {:sku "A-1" :name "Brush" :quantity 12}}
        {:row/id 102
         :cells {:sku "A-2" :name "Soap" :quantity 3}}]
 :sort {:column/id :name :direction :asc}
 :filters {}
 :selection nil}

;; User begins editing one cell.
{:type :ui.intent/cell-edit-started
 :view/id :inventory-grid
 :row/id 102
 :column/id :quantity}

;; A browser-side adapter or bthread may handle local editor behavior,
;; but the semantic model can still observe the edit session if useful.
{:type :ui.projection/edit-state
 :view/id :inventory-grid
 :row/id 102
 :column/id :quantity
 :status :editing
 :value 3}

;; User commits a new value.
{:type :ui.intent/cell-edit-committed
 :view/id :inventory-grid
 :row/id 102
 :column/id :quantity
 :value 4}

{:type :inventory.command/update-quantity
 :request/id #uuid "11111111-2222-3333-4444-555555555555"
 :item/id 102
 :quantity 4}

;; While the request is in flight, the grid can move into a pending state.
{:type :ui.projection/grid-state
 :view/id :inventory-grid
 :pending #{[:row 102 :column :quantity]}}

;; The update succeeds.
{:type :inventory.event/quantity-updated
 :request/id #uuid "11111111-2222-3333-4444-555555555555"
 :item/id 102
 :quantity 4}

{:type :ui.projection/grid-state
 :view/id :inventory-grid
 :rows [{:row/id 101
         :cells {:sku "A-1" :name "Brush" :quantity 12}}
        {:row/id 102
         :cells {:sku "A-2" :name "Soap" :quantity 4}}]
 :pending #{}}

;; Now imagine a conflict example.
{:type :ui.intent/cell-edit-committed
 :view/id :inventory-grid
 :row/id 101
 :column/id :quantity
 :value 9}

{:type :inventory.command/update-quantity
 :request/id #uuid "66666666-7777-8888-9999-000000000000"
 :item/id 101
 :quantity 9}

{:type :inventory.event/update-rejected
 :request/id #uuid "66666666-7777-8888-9999-000000000000"
 :item/id 101
 :reason :conflict
 :actual-value 15}

{:type :ui.projection/grid-state
 :view/id :inventory-grid
 :rows [{:row/id 101
         :cells {:sku "A-1" :name "Brush" :quantity 15}}
        {:row/id 102
         :cells {:sku "A-2" :name "Soap" :quantity 4}}]
 :conflicts #{[:row 101 :column :quantity]}}

{:type :ui.effect/show-message
 :level :warning
 :message "Quantity changed on the server before your edit was applied."}
```

This example hints at a useful split:

- durable shared state:
  + rows, columns, values, filters, sort order, validation markers, conflict markers
- transient local interaction:
  + cursor motion, text selection, open editor state, drag-fill preview, scroll position, IME composition

The more spreadsheet-like the interaction becomes, the more likely it is that some transient interaction logic should be allowed to live in browser-side bthreads, while committed domain updates and shared projections remain semantically coordinated across the whole system.

## Big Questions

### Do we model network requests?
We already separate commands from events: commands taking the imperative (`:create-task`) and events taking the past tense (`:task-created`). But do we want to keep track of network requests in flight? Modeling temporary frontend state? Etc.

### Rendering the page
Should we have bthreads take hiccup or html and render them?

### Server reboots
We cannot assume that a server process will stay alive. It could be the case that the server is rebooted at any time. How do we handle state at that point? How does the server process reboot to be in the right state? Should it be modeled in the model checker?

### Persistent Server Process
It could also be the case that we don't want to keep a process open on the server, and so it has to be able to boot up the bthreads it needs on the backend for every request from the client?
