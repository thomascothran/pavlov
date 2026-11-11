# Pavlov Explorer

A browser tool for walking a behavioral program one event at a time: see what
can happen next, choose, see how you got there, go back, and try the
alternatives. It is meant for people who do not read the code. What it does and
why is in [`doc/design/010_pavlov-explorer.md`](../../doc/design/010_pavlov-explorer.md).

The explorer runs in the same JVM as the programs it offers and explores them
the way the model checker does, so the programs must not do anything to the
outside world.

```clojure
(require '[tech.thomascothran.pavlov.explorer :as explorer])

(defn make-check-config
  []
  {:bthreads {:open-on-funding (make-open-on-funding-bthread)}
   :environment-bthreads {:pay-deposit (make-pay-deposit-bthread)}
   :safety-bthreads {}})

(def catalogue
  [{:id :account-opening
    :name "Account opening"
    :description "Identity and sanctions checks, then the first deposit."
    :make-config #'make-check-config
    :sources {:open-on-funding #'make-open-on-funding-bthread}}])

(explorer/start! {:catalogue #'catalogue :port 4990})
;; => "http://localhost:4990/"
```

- `:make-config` is called each time the program is built and returns the
  config map you would give to `model.check/check`. Bthreads are stateful, so
  it must make new ones on every call. If the config sets no `:max-nodes`,
  the explorer stops exploring at 5,000 nodes.
- `:sources` is optional. It maps a bthread name to the var whose source is
  shown as that bthread's code.
- Pass vars, as above, so that re-evaluated definitions are picked up.

A program is explored the first time it is opened and then kept. After
changing its code, use **Reload program** on its page, or `(explorer/reload!)`.

For programs to try, see `test/tech/thomascothran/pavlov/explorer/demo.clj`:

```clojure
(require '[tech.thomascothran.pavlov.explorer.demo :as demo])
(explorer/start! {:catalogue #'demo/catalogue})
```
