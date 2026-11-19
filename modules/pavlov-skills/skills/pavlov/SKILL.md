---
name: pavlov
description: Discover and learn the Pavlov behavioral programming library for Clojure/CLJS. Use when you need Pavlov concepts, bthreads/bprograms, or to locate docs, tests, and docstrings on the classpath.
---

# Pavlov discovery

Pavlov is a Clojure and ClojureScript behavioral programming library inspired by David Harel et al.’s paper ["Behavioral Programming"](https://www.wisdom.weizmann.ac.il/~harel/papers/Behavioral_Programming.pdf). It adapts the original behavioral programming model by running bprograms with a step function. It encourages scenario-based programming. You should be familiar and apply the ideas in *Come, Let's Play: Scenario-Based Programming Using LSCs and the Play-Engine,* Springer-Verlag, 2003, by David Harel and R. Marelly.

## Pavlov Idioms and Best Practices
The following are important pavlov idioms.

1. Do not default to building state machines. Prefer linear scenarios that express the story of what is happening. Compose those stories as needed. Remember you can wait and block from another bthread. Try to program in domain scenarios as much as possible. Scenarios should be expressive stories that are combined by the behavioral programming algorithm.
2. Pavlov scenarios should be written in a way that is so expressive that a human can read it and understand the story of what the program does. This applies to sizing scenarios, naming scenarios, and naming events.
3. Avoid abstractions on top of pavlov. Use data literals for bids unless there is a good reason not to. A human reading through a b/scenario and then coming across a function call is a cognitive cost. Consider whether it is worth it.
4. Group bthreads into domains, and domains in modules. Model check the modules primarily and the domains where it makes sense. Be careful about model checking huge applications unless there is a reason to to so.
5. All the bthreads in an application don't need to be created when the bprogram starts. A bthread can listen for the triggering event and then its bid can create the bthreads for that workflow. This also allows those bthread constructors to be passed arguments on init from the triggering event, which can be useful

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
(doc 'tech.thomascothran.pavlov.bthread/scenario)
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
