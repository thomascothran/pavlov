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

Authority is also not trivial. Browser and server cannot both independently own the same synchronization point. The design must leave room for authority to be determined by the behavioral rules of the application rather than assuming a universal "server always wins" or "browser always wins" policy.

## Problem Statement

How do we extend Pavlov with a web application architecture such that:

1. a whole web application can be designed behaviorally and checked as one JVM-side model,
2. the checked contract reaches user-visible UI behavior through semantic events and commands,
3. production behavior can be realized across server and browser runtimes with a shared event language,
4. the default architecture stays server-driven and thin-client-friendly, and
5. optional browser-side behavior can be introduced without abandoning end-to-end model-driven design?

## Next Up

### Phase B: Brainstorm Possible Solutions

This section surveys the option landscape without yet choosing a solution.

#### Common design goal across all options

Across the options below, the most important idea is to keep the checked boundary semantic.
UI bthreads should be able to declare things like:

- a field entering a warning state
- a form becoming disabled while a request is in flight
- a component entering a loading, error, or ready state
- a semantic request being issued
- a semantic user intent being observed

The actual DOM writes, HTML rendering, network IO, storage IO, and frontend library integration should be handled by adapters or IO bthreads below that semantic boundary.

#### Option 1: Session-authoritative semantic command bus

In this option, one server-side session bprogram is authoritative by default.
Browser events are translated into shared semantic events and submitted to the session bprogram.
The bprogram emits semantic UI and effect commands, and adapters realize them through Datastar, hiccup-based rendering, Replicant, or some other rendering strategy.

Characteristics:

- very compatible with the current model checker
- default thin-browser architecture
- SSR-friendly
- restartable server session model is conceptually straightforward
- easiest place to model reconnect, retries, duplicate delivery, and ordering as environment behavior

Risks:

- can become too imperative if the command vocabulary drifts into DOM details
- may need an additional mechanism for very responsive local UI behavior while requests are in flight

#### Option 2: Session-authoritative semantic projection model

In this option, the checked output is not primarily a command stream, but a semantic UI projection or view model.
The server-side bprogram computes UI state such as warnings, pending requests, enabled actions, and visible component states.
Renderers then turn that projection into HTML, Datastar signals, Replicant state, or another view representation.

Characteristics:

- strongest separation between behavioral rules and renderer choice
- naturally server-driven and SSR-friendly
- clean mental model for durable UI state

Risks:

- ephemeral UI behavior such as focus, one-shot notifications, scroll actions, animations, and cancellation affordances may not fit naturally
- may still need a side channel for effect-like UI outcomes

#### Option 3: Federated bprogram with optional browser partition

In this option, browser and server both may run bthreads in production, while still participating in one shared semantic model during checking.
The system would need an explicit session protocol for ordering, reconnect, dedupe, replay, and authority.

Characteristics:

- best fit for optional browser-side bthreads and richer in-flight interactivity
- keeps open the possibility of moving behavior between browser and server without rewriting the behavior itself
- aligns with the desire not to force all logic into the frontend while still allowing frontend behavior where useful

Risks:

- highest complexity by far
- authority, replay, reconnect, multi-tab, and duplicate delivery all become first-class design problems
- easiest option to get wrong if transport concerns leak into behavioral rules

#### Option 4: Datastar-backed semantic adapter

In this option, Pavlov owns the semantic model, while Datastar is used as a realization layer.
Pavlov bthreads emit semantic commands or semantic UI state, and a Datastar adapter lowers that into HTML patches, signal patches, and request/response mechanics.

Characteristics:

- very aligned with the preference for server-driven SPA or hypermedia-style applications
- practical near-term path to something useful
- good fit for thin-browser and SSR-friendly defaults

Risks:

- if Datastar concepts leak upward, the checked boundary can collapse into selectors, patches, signal names, and string-based expressions
- client-side scripting and signal expressions appear to be the most likely source of accidental complexity

This suggests that Datastar is likely better as an adapter target than as the core semantic model.

#### Option 5: Renderer-first adapter family (hiccup, Replicant, etc.)

In this option, the stable boundary is a semantic UI protocol, and different renderers consume it.
One adapter might render hiccup server-side, another might target Replicant or a CLJS renderer for richer client behavior, and another might target Datastar.

Characteristics:

- directly supports the goal of writing UI bthreads once and realizing them in different application styles
- creates a path for both server-heavy and richer frontend applications
- encourages explicit separation between semantic components and concrete renderers

Risks:

- the protocol may become too abstract or too weak if it tries to satisfy every renderer equally
- adapter authorship may be substantial if the semantic model is too large

#### Option 6: Pavlov-native UI runtime

In this option, Pavlov grows its own first-class UI runtime and semantic protocol for components, UI state, and effects.
Datastar, hiccup, Replicant, and any future web stack would become adapters beneath a Pavlov-owned UI layer.

Characteristics:

- best long-term control over pluggability
- best chance of keeping the checked boundary fully Pavlov-native
- cleanest story if browser-side bthreads later become a major feature

Risks:

- largest implementation surface
- highest risk of building too much infrastructure before validating the simpler paths

#### Cross-cutting architectural ideas

Some ideas cut across several of the options above and may end up being combined:

- a shared event schema across CLJ and CLJS
- semantic UI commands for transient outcomes
- semantic UI projections for durable screen state
- stable logical component identities that adapters map onto DOM ids, selectors, or renderer-local handles
- a session protocol that models ordering, retries, reconnect, duplicate suppression, and tab or user scope explicitly
- IO adapters or IO bthreads that perform actual DOM, network, or storage operations below the checked boundary

#### Important tension to preserve during Phase C

There appears to be a meaningful distinction between:

- durable UI state, which may be best represented as a semantic projection, and
- transient UI effects, which may be best represented as semantic commands

This suggests that the eventual solution may be hybrid rather than purely command-oriented or purely projection-oriented.

#### Working hypotheses coming out of brainstorming

At this stage, a promising shape is:

- Pavlov owns the semantic contract
- adapters realize that contract through Datastar, hiccup, Replicant, or a future Pavlov-native runtime
- the default production architecture remains server-first and thin-client-friendly
- optional browser bthreads remain possible, but do not have to be the starting point

So far, this still leaves open:

- the rendering strategy
- the transport protocol
- the component abstraction
- the browser/server authority model
- whether the best solution is command-oriented, projection-oriented, hybrid, or partitioned

### Phase C: Narrow Down the Solutions to a few candidates

### Phase D: Select a Solution and Design It
