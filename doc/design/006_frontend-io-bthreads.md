# Frontend IO Primitives

Status: Draft
Date: 2026-03-15

## Motivation

`005_ui-bprograms.md` leaves open whether optional browser-side behavior should be realized through a Datastar-style adapter, a browser-resident bprogram, or some hybrid of the two.

In this problem statement, we focus narrowly on the question:

- if we were to have a pavlov bprogram running on the frontend,
- what IO primitives would we need that provide low-level access to
  + changing the DOM,
  + making network calls,
  + being notified of DOM events
- that would not dictate whether a server-driven architecture (like Phoenix LiveView or Datastar), a frontend SPA, or any other approach would be taken.

Thus, a real application would need more bthreads to handle business logic, coordination, etc. But we don't want those to have to do IO. We want the IO to be done by as few IO bthreads as possible so the whole system is easier to test.

## Design Goals

The low-level browser layer should satisfy the following goals:

- provide a very small number of IO primitives that give higher-level bthreads a large amount of power
- stay thin and minimally opinionated over browser APIs
- introduce as little Pavlov-specific surface area as possible
- serve as a foundation that higher-level orchestration bthreads can build on

There is, however, one important complication.

Even if the primitive layer stays thin, Pavlov may still need some additional data in the request or some additional handling of the browser response in order to coordinate behavior cleanly.

That is the core problem for this document:

how thin can these primitives remain before behavioral coordination forces us to add just enough structure for correlation, lifecycle, and translation?

## Relevant Pavlov Context

Pavlov's behavioral core is already a good fit for a browser runtime at the semantic layer:

- bthreads coordinate through semantic events rather than direct calls into one another
- long-running bprograms can already receive external events over time
- subscribers can observe selected events and can inject follow-up events back into the program
- the runtime is already CLJC-oriented, so a browser-side semantic bprogram is not a foreign execution model

At the same time, the repository does not yet define the browser architecture around that core:

- there is no first-class DOM adapter layer
- there is no first-class fetch, storage, or transport adapter layer
- there is no browser lifecycle model for startup, teardown, reattach, or page navigation
- there is no agreed authority model for browser-local vs server-authoritative concerns

One more Pavlov detail matters here: requested internal events drain before the next external event is handled.

That is good for coherent state transitions, but it means low-level browser ingress and async completion need to fit that runtime model cleanly. Async events will need to return an event indicating they are in flight, and then another mechanism is needed to adapt the results into a pavlov event and then notify the behavioral program.

## Main Problem

How do we define

A. a small set of low-level browser IO primitives, runtime handlers, and helper functions such that:
  1. they remain thin wrappers over browser capabilities,
  2. they introduce as little new API surface as possible,
  3. they are still powerful enough to support higher-level behavioral coordination, and
  4. they can serve as a foundation for more than one frontend architecture, including richer frontend behavior and server-driven approaches?
B. how these bthreads need to attach to the browser, to the async queues in the browser (e.g., handling a fetch response), and how the events get back in the bprogram.

## Scope Of This Document

In scope here:

- candidate low-level DOM, event-ingress, fetch, and morph primitives
- which pieces should be bthreads and which pieces should be plain functions or adapter plumbing
- what extra arguments or response handling those primitives minimally require
- how those primitives can remain useful across multiple higher-level frontend approaches

Out of scope here:

- the full set of higher-level bthreads an application will need
- the full browser/server authority model
- a complete session protocol for reconnect, multi-tab behavior, or server restart
- a final frontend architecture decision between Datastar, frontend bprograms, or hybrids

Those are still important, but this document is only trying to define the low-level substrate that those larger designs could share.

## Critical Evaluation Of The Initial Sketch

### What the sketch gets right

The initial proposal has several good instincts:

- it introduces explicit browser IO seams rather than smearing effects through arbitrary application code
- it treats async completion as something that should come back into the bprogram as events
- it keeps open the idea of server-driven HTML and DOM morphing rather than assuming a full client-owned renderer
- it recognizes that some browser behavior is easier to express locally while requests are in flight

Those are all good reasons to spike this option.

### Where the sketch needs correction

The current shape is still too close to implementation details.

#### `DomOp`

This still looks like a good primitive.

For the spike, this primitive should live under `tech.thomascothran.pavlov.web.dom` and use a namespaced event type.

It should stay open rather than introducing a closed Pavlov-specific DOM command vocabulary.
That keeps the primitive thin and loosely coupled to browser evolution: if the browser gains new capabilities, callers can use them without needing Pavlov to add a new wrapper command first.

For the spike, we should start with CSS selectors as the target shape.
That keeps the primitive close to browser APIs and gives us something concrete to test.
If we later need XPath or another target form, we can generalize then.

The event could be a map with:

- `:type`: `:pavlov.web.dom/op`
- `:selector`: the selector passed to `document.querySelectorAll(selector)`
- `:kind`: either `:call` or `:set`
- `:member`: a vector path naming the DOM member to use, such as `[:focus]`, `[:classList :add]`, `[:disabled]`, or `[:innerHTML]`
- `:args`: the arguments to give the member when `:kind` is `:call`
- `:value`: the value to assign when `:kind` is `:set`
- `:ok-event`: an optional event to emit on success
- `:error-event`: an optional event to emit on error

This keeps `DomOp` close to browser capabilities while covering more than direct method calls.
It can express method invocation, nested member access, and property assignment without inventing a new DOM DSL.

Examples:

```clojure
{:type :pavlov.web.dom/op
 :selector "#search-input"
 :match :one
 :kind :call
 :member [:focus]
 :args []}

{:type :pavlov.web.dom/op
 :selector ".pending-row"
 :match :all
 :kind :call
 :member [:classList :add]
 :args ["loading"]}

{:type :pavlov.web.dom/op
 :selector "button[type='submit']"
 :match :one
 :kind :set
 :member [:disabled]
 :value true}
```

#### `attach-dom-events!`

This function connects DOM events to a Pavlov bprogram.

For the spike, it should live under `tech.thomascothran.pavlov.web.dom`.

```clojurescript
(attach-dom-events!
  {:submit! submit!
   :root js/document
   :events ["click"
            "submit"
            "input"
            "focusin"
            "focusout"]
   :resolve default-resolve-from-attributes
   :translators {"click" default-click-translator
                 "submit" default-submit-translator
                 "input" default-input-translator
                 "focusin" default-focus-translator
                 "focusout" default-focus-translator}})
```

An explanation of the arguments:

- submit!
  - required
  - called with the translated Pavlov event
  - by default that event is the raw DOM-shaped Pavlov event, not yet the semantic app event
- events
  - list of DOM event configs to listen for
  - e.g. click, submit, input, focusin, focusout
  - default: all events that bubble
- resolve
  - given a native DOM event, decide whether Pavlov cares
  - default: find the closest element with `<attr-prefix>-on-<event-name>`
  - return nil if irrelevant, else return info needed for translation
  - treat that attr as both the ingress gate and the semantic redirect source for that DOM event
  - copy any matched-element attrs beginning with the configured prefix into the translation context
- attr-prefix
  - optional
  - default: `"pavlov"`
  - controls the delegated DOM attr prefix, such as `pavlov-on-input` or `bp-on-input`
- translators
  - map of DOM event name to translator function
  - defaults provided
  - translator receives the native event plus the resolved context and returns a pure Pavlov event map
  - built-in translators keep `:type` as the raw DOM event type such as `:dom/input` or `:dom/click`

We provide defaults, but allow overrides.

The default flow becomes:

1. browser event arrives
2. resolve looks for attrs such as `pavlov-on-input` / `pavlov-on-click` by default, or `bp-on-input` / `bp-on-click` when `:attr-prefix "bp"` is configured
3. if no matching attribute, ignore
4. otherwise pick translator for that DOM event
5. translator returns a pure data raw DOM event with `:type` such as `:dom/input`
6. copied attrs are present on that raw DOM event as top-level keys such as `:pavlov-on-input` or `:bp-on-input`; built-in translators normalize copied `*-on-*` values to keywords
7. the copied event-specific key such as `:pavlov-on-input` or `:bp-on-input` is the semantic source of truth when a redirect is desired
8. submit! sends the raw DOM event to Pavlov
9. an optional redirect bthread may request a second semantic event whose `:type` comes from the matching copied key

For example, with the default `:attr-prefix "pavlov"`, delegated lookup uses attrs like `pavlov-on-input`, `pavlov-form-id`, and `pavlov-input-debounce-ms`.

With `:attr-prefix "bp"`, the same HTML contract becomes `bp-on-input`, `bp-form-id`, and `bp-input-debounce-ms`.

The resolved context should probably include at least:

```clojurescript
 {:dom/event-name "input"
  :matched-el el
  :attr-name "pavlov-on-input"
  :attr-value ":search/query-changed"
  :pavlov-on-input ":search/query-changed"}
```

The default raw DOM event emitted to Pavlov should look roughly like:

```clojurescript
{:type :dom/input
 :dom/event-name "input"
 :pavlov-on-input :search/query-changed
 :dom/input {:name "search"
             :value "milk"}}
```

#### `make-dom-event-redirect-bthread`

This bthread lives under `tech.thomascothran.pavlov.web.dom`.

It listens for raw DOM Pavlov events such as `:dom/input` and `:dom/click`.
When a raw DOM event includes the matching copied key such as `:pavlov-on-input`, `:bp-on-input`, `:pavlov-on-click`, or `:bp-on-click`, it requests a second event that is unchanged except for `:type`, which becomes the semantic keyword named by that copied value.
If the matching copied key is absent or invalid, it emits nothing.

This keeps the responsibilities split cleanly:

- `attach-dom-events!` captures and normalizes browser facts
- attrs such as `pavlov-on-input` or `bp-on-input` both opt that browser event into Pavlov ingress and carry its semantic redirect target
- built-in translators emit pure raw DOM Pavlov events
- the copied event-specific key such as `:pavlov-on-input` or `:bp-on-input` carries the semantic event name when one is needed
- `make-dom-event-redirect-bthread` performs semantic retargeting explicitly
- application bthreads listen for the semantic events they care about

#### BrowserFetch

For the spike, this should live under `tech.thomascothran.pavlov.web.fetch`.

The primitive event should use a namespaced event type, and the browser-side implementation should be an attached runtime handler such as `attach-browser-fetch!` rather than a normal behavioral thread.

This wraps the browser's `fetch` functionality.

It needs to do at least the following:

1. take a pavlov event as a request,
2. send a fetch request,
3. request an in-flight event immediately,
4. submit a response event when `fetch` resolves with an HTTP response, and
5. submit an error event when no response is returned.

The request event should stay thin and browser-like.

It should take:

- `:type`: `:pavlov.web.fetch/request`
- `:request/id`: a correlation id
- `:url`: the URL passed to `fetch`
- `:fetch-opts`: the options map passed through to `fetch`
- `:decode`: initially `:json`
- `:in-flight-event-type`: event type to emit when the request starts
- `:response-event-type`: event type to emit when an HTTP response is returned
- `:error-event-type`: event type to emit when no response is returned

If `fetch` resolves with an HTTP response, that is not an error even if the status is `4xx` or `5xx`.
Those cases should still use `:response-event-type`.

The response event should contain pure data only.
It should not expose browser objects like `Response`, and the error event should also contain data only, with no functions or methods.

Examples:

```clojure
{:type :pavlov.web.fetch/request
 :request/id #uuid "11111111-1111-1111-1111-111111111111"
 :url "/tasks"
 :fetch-opts {:method "POST"
              :headers {"content-type" "application/json"
                        "accept" "application/json"}
              :body (js/JSON.stringify
                     #js {:task-name "x"
                          :task-type "inside"})}
 :decode :json
 :in-flight-event-type :task-form/submit-pending
 :response-event-type :task-form/submit-response
 :error-event-type :task-form/submit-error}

{:type :task-form/submit-pending
 :request/id #uuid "11111111-1111-1111-1111-111111111111"}

{:type :task-form/submit-response
 :request/id #uuid "11111111-1111-1111-1111-111111111111"
 :status 422
 :ok false
 :headers {"content-type" "application/json"}
 :body {:errors {:task-name ["Must be at least 3 characters"]}}}

{:type :task-form/submit-response
 :request/id #uuid "11111111-1111-1111-1111-111111111111"
 :status 201
 :ok true
 :headers {"content-type" "application/json"}
 :body {:task/id 123
        :task-name "Take out trash"
        :task-type "inside"}}

{:type :task-form/submit-error
 :request/id #uuid "11111111-1111-1111-1111-111111111111"
 :error {:kind :network
         :message "Failed to fetch"}}

{:type :pavlov.web.fetch/request
 :request/id #uuid "22222222-2222-2222-2222-222222222222"
 :url "/search/users?q=thom"
 :fetch-opts {:method "GET"
              :headers {"accept" "application/json"}}
 :decode :json
 :in-flight-event-type :user-search/requested
 :response-event-type :user-search/results-received
 :error-event-type :user-search/request-failed}

{:type :user-search/requested
 :request/id #uuid "22222222-2222-2222-2222-222222222222"}

{:type :user-search/results-received
 :request/id #uuid "22222222-2222-2222-2222-222222222222"
 :status 200
 :ok true
 :headers {"content-type" "application/json"}
 :body {:results [{:user/id 1 :name "Thomas"}
                  {:user/id 2 :name "Thompson"}]}}

{:type :user-search/request-failed
 :request/id #uuid "22222222-2222-2222-2222-222222222222"
 :error {:kind :network
         :message "Failed to fetch"}}
```

For the initial spike, the response shape should minimally include:

- `:request/id`
- `:status`
- `:ok`
- `:headers`
- `:body`

And the error shape should minimally include:

- `:request/id`
- `:error`

#### ServerBridge

`BrowserFetch` should remain as the low-level HTTP primitive, but it is not the whole story for client-server coordination.
For a browser-resident bprogram, we also want a higher-level server bridge that lets frontend bthreads send semantic events to the backend and receive semantic events back.

The current bridge direction is transport-agnostic at the Pavlov event seam and lives under `tech.thomascothran.pavlov.web.server`.
One connection is modeled by a generic bridge bthread created by `make-server-bridge-bthread`, while browser websocket wiring lives separately under `tech.thomascothran.pavlov.web.server.websocket`.

The public bridge contract is:

- outbound semantic send requests via `:pavlov.web.server/send-event`
- inbound semantic receipts via `:pavlov.web.server/event-received`
- lifecycle readiness via `:pavlov.web.server/connected` and `:pavlov.web.server/disconnected`

Example outbound request:

```clojure
{:type :pavlov.web.server/send-event
 :event {:type :task.command/create
         :request/id #uuid "11111111-1111-1111-1111-111111111111"
         :task/name "Take out trash"
         :task/type "inside"}
 :on-error-event-type :task.command/send-failed}
```

Example inbound receipt:

```clojure
{:type :pavlov.web.server/event-received
 :event {:type :task.event/created
         :request/id #uuid "11111111-1111-1111-1111-111111111111"
         :task/id 123
         :task/name "Take out trash"
         :task/type "inside"}}
```

The bridge bthread is responsible for readiness gating and outbound send behavior:

- while disconnected, it blocks `:pavlov.web.server/send-event`
- after `:pavlov.web.server/connected`, it encodes `(:event e)` and calls the injected transport `send!`
- after `:pavlov.web.server/disconnected`, it blocks outbound sends again until the next connection event
- if encoding or sending fails and `:on-error-event-type` is present, it emits a pure-data follow-up event of that type

Transport details stay below that seam.
The current browser-specific direction is a websocket-backed transport in `tech.thomascothran.pavlov.web.server.websocket` that:

- creates a browser `WebSocket`
- maps open/message/close callbacks to the generic bridge callbacks
- sends encoded outbound payloads through the socket
- keeps codec choice injected via `:encode` and `:decode` rather than fixed by the bridge contract

That gives us this current client-server flow:

1. a frontend bthread requests `:pavlov.web.server/send-event`
2. the generic bridge waits for connection readiness, then encodes and sends the semantic event through the injected transport
3. the browser websocket transport turns socket lifecycle and message callbacks into bridge events
4. inbound payloads are decoded and submitted back into the client bprogram as `:pavlov.web.server/event-received`

`BrowserFetch` remains a separate low-level HTTP primitive for request/response interactions; it is no longer the planned transport substrate for the server bridge itself.

#### Naming And Organization

For the spike, this work should live in a new module: `modules/pavlov-web/`.

That keeps browser- and transport-specific code out of the core behavioral runtime and matches the existing split between `pavlov` and `pavlov-devtools`.

The initial namespaces should be:

- `tech.thomascothran.pavlov.web.dom`
- `tech.thomascothran.pavlov.web.fetch`
- `tech.thomascothran.pavlov.web.server`
- `tech.thomascothran.pavlov.web.server.websocket`

The initial runtime attachment functions should be:

- `attach-dom-events!`
- `attach-dom-op-handler!`
- `attach-browser-fetch!`
- `make-server-bridge-bthread`
- `make-browser-websocket-transport`

The low-level primitive events should use namespaced keywords:

- `:pavlov.web.dom/op`
- `:pavlov.web.fetch/request`
- `:pavlov.web.server/send-event`

#### Not everything here should necessarily be a bthread

For the spike, the current working split should be:

- ingress browser functions such as `attach-dom-events!`
- attached runtime handlers such as `attach-dom-op-handler!` and `attach-browser-fetch!`
- bridge behavior via `make-server-bridge-bthread` plus transport-specific browser plumbing such as `make-browser-websocket-transport`
- higher-level toolkit and application bthreads that request the low-level primitive events

Some of the things described above are better understood as browser adapters or runtime infrastructure around the bprogram than as behavioral participants in the model.
`DomMorph` also still looks more like a realization adapter than a behavioral participant.

That distinction matters because we do not want to force every browser concern into the same abstraction when some of them are really plumbing.

## Conceptual / Design Problems To Solve

### 1. What counts as a primitive?

Which capabilities belong in the foundational layer and which should remain higher-level orchestration?

### 2. Which pieces are bthreads, runtime handlers, and plain functions?

The current spike direction is:

- ingress via startup functions
- egress via attached runtime handlers
- behavioral coordination via higher-level bthreads that request primitive events

What still needs to be tested is whether that split remains clean in practice.

### 3. How closely should targets mirror browser addressing?

### 4. How much extra request data is minimally necessary?

### 5. How much response shaping is minimally necessary?

Browser APIs often return JavaScript objects that are not yet good Pavlov events.
Which transformations should happen inside the primitive layer so higher-level bthreads can coordinate on the results?

### 6. How should DOM ingress be connected?

If earlier ideas like `DomAttach` disappear as primitives, what function-based event bridge replaces them?
How much of that bridge is generic and reusable, and how much is application-specific event translation?

### 7. Hypermedia-driven applications

We want to make it easy for the backend to return html to be swapped in. What are the best ways to do that?

### 8. How do we keep the layer testable?

The primitive layer should be easy to fake or replace in tests.
That is one of the main reasons to keep the browser interface small.

Perhaps `happydom` is the answer.

### 9. How do we keep this layer foundational rather than application-shaped?

The primitive layer should support frontend-SPA, server-driven, and hybrid approaches without baking any of them in.
That means the problem statement has to stay focused on the substrate and avoid drifting into a whole frontend architecture.

### 10. Client-server communication

The current spike direction is:

- outbound semantic events from the client via `:pavlov.web.server/send-event`
- inbound semantic events from the server via `:pavlov.web.server/event-received`
- connection lifecycle modeled via `:pavlov.web.server/connected` and `:pavlov.web.server/disconnected`
- browser transport currently provided by a websocket-backed implementation under `tech.thomascothran.pavlov.web.server.websocket`

That is enough to spike the client-server path while keeping the bridge contract transport-agnostic and the codec choice deferred.

What remains open is ordering, reconnect, replay, backend scoping of remote events, and which production codec should be the default for the websocket-backed transport.

## Plan for a spike

We should be able to spike out a few things:

1. An autocomplete typeahead input
2. A form that does server-side validation and submits without reloading the page
3. Navigation
4. Datagrid-like functionality, but driven from the server side.

If we can cover those cases, then there's a good chance we have it.

## What A Useful Spike Should Prove

A useful spike should answer focused low-level questions:

1. Can a very small set of primitives cover the essential browser IO needs without forcing lots of one-off primitives?
2. Can those primitives stay close to browser APIs while still returning Pavlov-usable events?
3. What extra request metadata or response shaping is actually required in practice?
4. Is `DomOp` workable as a generic primitive, and if so what target shape should it use?
5. Does the split between ingress functions, runtime handlers, and higher-level bthreads stay clean in practice?
6. Can the same primitive layer be used beneath more than one higher-level frontend approach, including the generic server bridge with a websocket-backed browser transport?

If the spike cannot identify a small primitive layer that meets those requirements, that is a useful result.
It would suggest that either the primitive layer needs to be more opinionated or that the browser-bprogram path should remain secondary to a higher-level adapter approach.
