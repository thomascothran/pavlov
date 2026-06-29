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
