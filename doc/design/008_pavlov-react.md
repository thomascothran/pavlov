# Pavlov React Adapter

Status: Draft
Date: 2026-03-22

## Problem We Are Trying To Solve

We have `modules/pavlov-web`, which connects browser behavior to a Pavlov
bprogram by:

- capturing DOM events,
- translating them into Pavlov events,
- optionally redirecting raw browser events into semantic events, and
- realizing Pavlov output through DOM operations and browser fetch.

That works well for DOM-owned UIs, but React changes one important constraint:
inside a React-managed subtree, React owns DOM reconciliation.

So the question is:

- how should events from a React tree enter Pavlov,
- how should Pavlov affect the UI without issuing direct DOM mutations as the
  primary rendering mechanism, and
- how can we do this with React alone, without introducing a separate state
  library?

## Context

### What `pavlov-web` does today

The current browser bridge has three main parts.

1. Delegated DOM ingress in
   `modules/pavlov-web/src/tech/thomascothran/pavlov/web/dom.cljc`
   via `attach-dom-events!`.
2. Explicit semantic redirection in the same namespace via
   `make-dom-event-redirect-bthread`.
3. Imperative DOM egress via `make-dom-op-bthread`, which realizes
   `:pavlov.web.dom/op` and `:pavlov.web.dom/ops` events.

There is also a separate fetch bridge in
`modules/pavlov-web/src/tech/thomascothran/pavlov/web/fetch.cljc`.

This current design has a good split:

- ingress captures browser facts,
- semantic bthreads coordinate on Pavlov events,
- egress adapters realize effects.

That split should be preserved.

### Why React changes the shape of the adapter

React is not just a different event source. It is also a rendering authority.

If Pavlov directly mutates DOM nodes that React believes it owns, then React and
Pavlov are co-owning the same subtree. That creates a brittle system with
unclear authority, where either side can overwrite the other.

This means the current `:pavlov.web.dom/op` model is not a good primary egress
mechanism for React-rendered UI.

At the same time, the broader semantic direction from `005_ui-bprograms.md`
still fits React very well:

- semantic user intents enter the bprogram,
- semantic UI projections and effects come out,
- adapters realize those outputs in a renderer-specific way.

## Constraints

### We Must Do

- keep React as the owner of DOM reconciliation for React-managed subtrees,
- keep Pavlov as the owner of behavioral coordination,
- stay usable with React alone,
- preserve a semantic boundary above renderer-specific details,
- keep network and other side effects expressible as Pavlov events.

### We Must Not Do

- make direct DOM mutation the normal rendering path for a React subtree,
- require Redux, Zustand, or another state library,
- make the checked boundary depend on JSX structure or CSS selectors,
- force application logic into React components.

## Criteria Of A Good Solution

A good React adapter should:

1. make React event ingress straightforward,
2. keep UI output semantic rather than DOM-shaped,
3. work with plain React primitives,
4. minimize required co-change between components and bthreads,
5. keep fetch and other non-UI effects reusable,
6. allow transient imperative effects where needed,
7. stay compatible with the semantic modeling direction in
   `005_ui-bprograms.md`.

## Options Considered

### Option A: Explicit React Intents, Semantic Projections, And Semantic Effects

React components submit semantic events directly from handlers.

Examples:

```clojure
{:type :ui.intent/button-clicked
 :button/id :save}

{:type :ui.intent/form-changed
 :form/id :task-form
 :field :task-name
 :value "Buy milk"}
```

Pavlov emits semantic UI output such as:

```clojure
{:type :ui.projection/form-state
 :form/id :task-form
 :status :editing
 :fields {:task-name {:value "Buy milk"}}
 :actions {:submit {:enabled true}}}

{:type :ui.effect/focus
 :target [:form/id :task-form :field :task-name]}
```

A small adapter layer then:

- subscribes to the bprogram,
- folds durable `:ui.projection/*` events into a snapshot store,
- exposes that store to React through plain React mechanisms,
- handles transient `:ui.effect/*` events imperatively.

React renders from the snapshot.

Pros:

- best fit for React's state -> view model,
- strongest semantic boundary,
- lowest coupling to DOM shape,
- easiest to model-check at the UI boundary,
- easiest to swap to another renderer later.

Cons:

- requires an explicit projection schema,
- slightly less automatic than delegated DOM capture,
- requires deciding what is durable projection vs transient effect.

### Option B: Reuse Delegated DOM Ingress On The React Root, But Use Semantic Egress

React components render `pavlov-on-*` attributes, and `attach-dom-events!` is
used on the root React container.

Pavlov still emits semantic projections and effects rather than DOM ops.

Pros:

- closest to current `pavlov-web`,
- allows incremental reuse of existing DOM translators and redirect bthreads,
- works for mixed static HTML and React islands.

Cons:

- behavioral contracts become tied to rendered attrs and DOM structure,
- JSX refactors may require bthread changes,
- React components become partially shaped by a DOM-oriented adapter,
- capture-selector style patterns are a poor fit for React component structure.

### Option C: React-Specific Hook API With Selector Subscriptions

Build a richer React adapter surface, for example:

- `usePavlovDispatch`,
- `usePavlovSelector`,
- `PavlovProvider`.

Pros:

- ergonomic for React-heavy applications,
- can support fine-grained rerenders.

Cons:

- introduces a larger React-specific API surface,
- pulls the design toward a state-library shape,
- increases coupling to React and makes replacement harder,
- not necessary for an initial adapter.

### Option D: Keep Durable UI State In React, Use Pavlov Only For Imperative Effects

React owns the durable render state through local component state or reducers.
Pavlov emits only imperative commands such as focus, scroll, navigate, or fetch.

Pros:

- easy to adopt incrementally,
- good for narrow local enhancements.

Cons:

- splits behavioral authority across React state and Pavlov,
- many changes require coordinated edits in both places,
- weak semantic model boundary,
- poor fit for model-driven UI design.

## Selected Option

We select **Option A: explicit React intents, semantic projections, and
semantic effects**.

This means:

- ingress from React should usually be explicit semantic event submission from
  React handlers,
- durable UI output should be expressed as `:ui.projection/*` events,
- transient UI output should be expressed as `:ui.effect/*` events,
- React should render from adapter-managed snapshot state,
- low-level browser IO like fetch can still be handled by separate attached
  adapters.

## Why This Option Is Best

### It matches React's authority model

React should own rendering. Pavlov should not mutate the DOM behind React's
back as the normal realization path.

Option A preserves that boundary cleanly.

### It matches Pavlov's semantic direction

`005_ui-bprograms.md` argues that the checked boundary should reach user-visible
behavior through semantic intents, projections, and effects rather than exact
DOM mutations.

Option A directly instantiates that approach for React.

### It reduces coupling

With Option A, changing JSX structure, class names, or DOM nesting usually does
not require changes in the bthreads.

By contrast, delegated DOM capture ties behavior more tightly to rendered
markup.

### It keeps the adapter small

We do not need a full React state library. A small external store and a few
React integration points are sufficient.

## Detailed Description Of The Selected Option

### Ingress

Ingress should usually come from explicit React handlers.

For example:

```clojure
{:type :ui.intent/form-input-changed
 :form/id :create-task
 :field :task-name
 :value "Buy milk"}

{:type :ui.intent/form-submitted
 :form/id :create-task
 :values {:task-name "Buy milk"
          :task-type "inside"}}
```

This replaces the main purpose of `attach-dom-events!` for React-owned UI.

The important decision is that the adapter boundary is semantic. React handlers
should extract the needed data immediately and submit plain Pavlov events.

### Durable UI Egress

Durable UI state should not be emitted as DOM commands. It should be emitted as
semantic projection events.

An adapter store should subscribe to the bprogram and fold those projection
events into a snapshot. React components render from that snapshot.

This store does not need to be a separate library. It can be a tiny adapter with
three responsibilities:

1. hold the current snapshot,
2. notify listeners when it changes,
3. expose a read function for React.

React can consume it through `useSyncExternalStore` or an equivalent plain React
mechanism.

### Transient UI Egress

Some UI outputs are not durable render state.

Examples:

- focusing an input,
- scrolling to an element,
- navigating,
- selecting text,
- opening a file picker.

These should remain effect events, for example under `:ui.effect/*`.

A separate effect adapter can subscribe to the bprogram and realize those
effects imperatively using refs, browser APIs, or router APIs.

This gives us a clean split:

- projections are durable and rendered by React,
- effects are transient and realized imperatively.

### Network And Other Browser IO

`tech.thomascothran.pavlov.web.fetch` remains useful under this design.

React does not change the need for browser fetch, nor does it change the basic
pattern that async completion should re-enter the bprogram as events.

So the React adapter should reuse existing low-level IO bridges where possible,
rather than replacing them.

## Coupling Analysis

### Preferred Direction

Under the selected option, the primary dependencies are:

- `React components -> semantic intent schema`
- `projection adapter -> semantic projection schema`
- `effect adapter -> semantic effect schema`
- `fetch and other runtime adapters -> browser APIs`

This is the desired direction because renderer details are below the semantic
boundary rather than above it.

### Coupling Hotspots Avoided

We specifically avoid these tighter forms of coupling:

- `React markup -> DOM resolver`
- `JSX attrs -> semantic event mapping`
- `DOM structure -> bthread logic`
- `direct DOM ops -> React reconciliation`

Those couplings are acceptable in `pavlov-web`'s DOM-oriented adapter, but they
should not define the primary React integration path.

## Consequences

### Positive Consequences

- React integration stays idiomatic.
- The checked boundary remains semantic.
- UI logic remains in bthreads rather than in component-local orchestration.
- The adapter can stay very small.
- React and non-React renderers can share more of the same behavioral model.

### Negative Consequences

- Some ergonomics must still be designed, especially snapshot shape and helper
  APIs.
- We need conventions for how projection events update the snapshot.
- A pure DOM-delegation style becomes secondary for React-managed trees.

## Secondary Path: Compatibility Mode

Option B should remain available as a secondary path when it is useful to:

- attach Pavlov to static HTML,
- support React islands inside broader DOM-managed pages,
- reuse existing DOM-oriented ingress patterns during migration.

But it should not be the recommended default for a React-native adapter.

## Minimal API Direction

The initial React adapter should aim for a very small surface area.

Conceptually, it needs only:

1. a way to submit events into a bprogram,
2. a store that derives a durable UI snapshot from selected events,
3. a React hook or provider layer to read that snapshot,
4. an effect attachment mechanism for transient UI effects.

One plausible shape is:

```clojure
(make-ui-store {:initial-snapshot ...
                :reduce-projection (fn [snapshot event] ...)})

(attach-ui-store! {:program program
                   :store store})

(attach-ui-effects! {:program program
                     :handlers {:ui.effect/focus ...
                                :ui.effect/navigate ...}})
```

And on the React side, a thin layer around the store could expose a hook such as
`usePavlovSnapshot`.

This should be treated as direction, not a final API commitment.

## Open Questions

We are intentionally not fully resolving these yet:

1. Should the durable UI snapshot be one root tree, or a small family of named
   projections?
2. What naming and shape conventions should `:ui.projection/*` follow?
3. What transient effects belong in the first adapter version?
4. How should navigation be represented so that it remains renderer- and
   router-agnostic?
5. How much helper API is worth adding before the adapter starts to resemble a
   full state library?

## Decision Summary

For React, Pavlov should not primarily integrate at the DOM-mutation layer.

Instead, the recommended architecture is:

- React handlers submit semantic intents,
- Pavlov emits semantic projections and effects,
- a small React adapter turns projections into snapshot state,
- React renders the snapshot,
- effect adapters handle the imperative remainder,
- existing low-level IO adapters like fetch remain reusable.

That gives Pavlov a React integration that is small, semantic, React-friendly,
and aligned with the broader UI architecture already emerging in this
repository.
