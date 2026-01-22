# Pavlov model checker REPL quickstart

## Require block

```clojure
(require '[tech.thomascothran.pavlov.model.check :as check]
         '[tech.thomascothran.pavlov.bthread :as b]
         '[tech.thomascothran.pavlov.event :as e]
         '[clojure.java.io :as io])
```

## Pull examples from tests

- The canonical examples live in `tech/thomascothran/pavlov/model/check_test.clj`.
- Copy a `check/check` invocation from the tests into the REPL and iterate.

```clojure
(slurp (io/resource "tech/thomascothran/pavlov/model/check_test.clj"))
```

## Interpret results

- Use `references/interpret-results.md` for the result shape and keys.
