# Interpreting model-checker results

`tech.thomascothran.pavlov.model.check/check` returns `nil` when no violations are found. Otherwise it returns a map with one or more non-empty categories.

## Result keys

- `:deadlocks` — vectors of `{:path [...] :state {...}}` when no events are possible.
- `:livelocks` — vectors of `{:path [...] :cycle [...]}` for infinite cycles.
- `:safety-violations` — vectors of `{:event {...} :path [...] :state {...}}` when a safety bthread emits `:invariant-violated true`.
- `:liveness-violation` — a single hot-state witness map like `{:node-id ... :path-edges [...] :state {...}}` when a hot-state liveness obligation is not met.
- `:truncated` — `true` if exploration stopped due to `:max-nodes`.

## What to do next

- Inspect the violating `:path`, `:cycle`, `:path-edges`, or `:cycle-edges` witness to see how the violation is reached.
- Compare with the definitions in `tech/thomascothran/pavlov/model/check_test.clj` for expected shapes.
- Use `clojure.repl/doc` or `source` on `tech.thomascothran.pavlov.model.check/check` for authoritative details.
