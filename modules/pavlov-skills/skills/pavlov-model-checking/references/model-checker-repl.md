# Pavlov model checker REPL quickstart

## Require block

```clojure
(require '[tech.thomascothran.pavlov.model.check :as check]
         '[tech.thomascothran.pavlov.bthread :as b]
         '[tech.thomascothran.pavlov.event :as e]
         '[clojure.java.io :as io])
```

## Pull examples from tests

- Start with `(clojure.repl/doc tech.thomascothran.pavlov.model.check/check)` for the current public API and example shapes.
- For executable examples, prefer hot-state liveness and `:possible` cases from:
  - `tech/thomascothran/pavlov/model/check_test.clj`
  - `tech/thomascothran/pavlov/model/check/liveness_test.clj`
- In the REPL, express universal progress with `:hot true` on bids and existential reachability with `:possible`.
- Do not use legacy top-level `:liveness` forms when writing new checks.

```clojure
(slurp (io/resource "tech/thomascothran/pavlov/model/check_test.clj"))
```

## Interpret results

- Use `references/interpret-results.md` for the result shape and keys.
