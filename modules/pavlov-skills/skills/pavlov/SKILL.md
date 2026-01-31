---
name: pavlov
description: Discover and learn the Pavlov behavioral programming library for Clojure/CLJS. Use when you need Pavlov concepts, bthreads/bprograms, or to locate docs, tests, and docstrings on the classpath.
---

# Pavlov discovery

Pavlov is a Clojure and ClojureScript behavioral programming library inspired by David Harel et al.’s paper ["Behavioral Programming"](https://www.wisdom.weizmann.ac.il/~harel/papers/Behavioral_Programming.pdf). It adapts the original behavioral programming model by running bprograms with a step function.

## Choose your path

- Bare-bones API details or quick usage cues: start with docstrings.
- Principles and conceptual background: read the documentation resources. What is behavioral programming? Why is it used?
- Concrete examples: browse tests (see `references/access-test-examples.md`).

## Docstrings (fastest)

- Use `clojure.repl/doc` in the Clojure REPL on the core namespaces and functions first.

```clojure
(require '[clojure.repl :refer [doc source find-doc apropos]])

(doc 'tech.thomascothran.pavlov.bthread)
(doc 'tech.thomascothran.pavlov.bprogram.ephemeral)
(doc 'tech.thomascothran.pavlov.bthread/bids)
(doc 'tech.thomascothran.pavlov.bprogram.ephemeral/execute!)
```

## Documentation resources (background)

- Use `clojure.java.io` and `slurp` with `io/resource`.
- All docs live under `tech/thomascothran/pavlov-skills/doc/`.

```clojure
(require '[clojure.java.io :as io])

(slurp (io/resource "tech/thomascothran/pavlov-skills/doc/README.md"))
(slurp (io/resource "tech/thomascothran/pavlov-skills/doc/what-is-a-bthread.md"))
```

## Test examples (concrete)

- Once you have a path, read tests with `io/resource` + `slurp`.

```clojure
(require '[clojure.java.io :as io])

(slurp (io/resource "tech/thomascothran/pavlov/bthread_test.cljc"))
(slurp (io/resource "tech/thomascothran/pavlov/bprogram/ephemeral_test.clj"))
```

These files can be large, so search the strings for the functions you're looking for.

For more examples, use `references/access-test-examples.md` (resource path `./references/access-test-examples.md`) to list test sources on the classpath.
