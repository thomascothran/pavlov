# Pavlov Web

## HTTP requests through Pavlov IO

Compose the pending-state bthread with a Fetch handler registered through
`tech.thomascothran.pavlov.io/make-subscriber!`:

```clojure
(require '[tech.thomascothran.pavlov.bprogram :as bp]
         '[tech.thomascothran.pavlov.bprogram.ephemeral :as bpe]
         '[tech.thomascothran.pavlov.io :as io]
         '[tech.thomascothran.pavlov.web.fetch :as fetch])

(def program
  (bpe/make-program!
   [[:fetch-state (fetch/make-fetch-bthread)]]
   {:subscribers
    {:http (io/make-subscriber!
            {:pavlov.web.fetch/request
             (fetch/make-fetch-handler js/fetch)})}}))

(bp/submit-event! program
                  {:type :pavlov.web.fetch/request
                   :request/id "load-tasks"
                   :url "/tasks"
                   :fetch-opts #js {:method "GET"}
                   :in-flight-event-type :tasks/loading
                   :response-event-type :tasks/loaded
                   :error-event-type :tasks/load-failed})
```

The bthread requests correlated pending state but performs no IO. The subscriber
starts Fetch and reports response/error events to its originating program. The
handler does not submit pending events itself. Pending state participates in
normal event selection; it can still be blocked by another bthread.

Responses contain `:request/id`, `:status`, `:ok`, `:headers`, and a JSON-decoded
`:body`. HTTP error statuses remain response events. Network failures,
synchronous Fetch exceptions, and JSON parsing failures become error events.
Fetch options pass through unchanged: use native JavaScript options with browser
Fetch. Cancellation, retries, and late-completion handling remain caller-owned.

**Migration:** `make-fetch-bthread` now accepts zero arguments only. Remove the
old submit/Fetch constructor arguments and register the IO handler separately. `make-fetch-fn` retains the legacy
explicit-submit subscriber contract, including directly submitted pending state.
Do not install both the legacy subscriber and the new IO path for the same
requests, or pair the legacy subscriber with the pending-state bthread.

## Experimental Squint support

Pavlov core and the browser-facing parts of Pavlov Web can be compiled with
[Squint](https://github.com/squint-cljs/squint). The implementation and tests
are shared with full ClojureScript; Squint-specific files only configure test
and ESM entrypoints.

```bash
cd modules/pavlov
npm ci && npm run test:squint

cd ../pavlov-web
npm ci && npm run test:squint

cd ../../examples/web
npm ci && npm run build:squint
```

The example server exposes the Squint bundle at `/browser-only-squint` and
`/game-of-life-squint`; the original Shadow CLJS pages remain at
`/browser-only` and `/game-of-life`. Squint consumes Pavlov source roots rather
than Clojars JARs. A consuming monorepo can configure paths directly:

```clojure
{:paths ["src"
         "../pavlov/modules/pavlov/src"
         "../pavlov/modules/pavlov-web/src"]}
```

Support remains experimental and currently inherits Pavlov core's primitive
string/keyword bthread-name and event-type profile. Collection-valued names or
event types and the `b/thread` macro are not yet guaranteed under Squint.
