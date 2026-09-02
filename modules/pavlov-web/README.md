# Pavlov Web

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
