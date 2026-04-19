# Interpreting model-checker results

`tech.thomascothran.pavlov.model.check/check` returns `nil` when no violations are found. Otherwise it returns a map with one or more non-empty categories.

## Result keys

- `:deadlocks` — vectors of `{:path [...] :state {...}}` when no events are possible.
- `:livelocks` — vectors of `{:path [...] :cycle [...]}` for infinite cycles.
- `:impossible` — a set of event types from your configured `:possible` check that never appear on any reachable edge.
- `:safety-violations` — vectors of `{:event {...} :path [...] :state {...}}` when a safety bthread emits `:invariant-violated true`.
- `:liveness-violation` — a single hot-state witness map like `{:node-id ... :path-edges [...] :state {...}}`, optionally with `:cycle-edges [...]`, when a hot-state liveness obligation is not met.
- `:truncated` — `true` if exploration stopped due to `:max-nodes`.

## What to do next

- Inspect the violating `:path`, `:cycle`, `:path-edges`, or `:cycle-edges` witness to see how the violation is reached.
- If you get `:impossible`, compare that set with your configured `:possible` events to see which expected scenarios were never reached.
- Compare with the definitions in `tech/thomascothran/pavlov/model/check_test.clj` for expected shapes.
- Use `clojure.repl/doc` or `source` on `tech.thomascothran.pavlov.model.check/check` for authoritative details.
