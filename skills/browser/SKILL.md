---
name: browser
description: Control a browser programmatically. Use for integration testing, manual validation, and experimentation.
---

Control a browser with etaoin, a clojure library, using Clojure.

Always start browser sessions with `e/with-chrome-headless` so WebDriver and Chromium processes are cleaned up automatically.
Do not create a top-level `(def driver ...)` in examples.

## Getting Started

An example at the REPL:

```clojure
(require '[etaoin.api :as e]
         '[etaoin.keys :as k]
         '[clojure.string :as str])

(e/with-chrome-headless driver
  (e/driver-type driver) ;; => :chrome

  (e/go driver "http://localhost:3000/demo/datatable")

  ;; make sure we aren't using a large screen layout
  (e/set-window-size driver {:width 1280 :height 800})

  ;; wait for the search input to load
  (e/wait-visible driver [{:tag :input :name :search}]))
```


## Selecting elements

Use css selectors or xpath to select elements.

```clojure
{:css "input#uname[name='username']"}
```

```clojure
{:xpath ".//input[@id='uname']"}
```

For example:

```clojure
(e/fill driver
        {:css "input#uname[name='username']"}
        " CSS selector")
```

## Important tips

### Use waits

Use `e/wait-visible` to wait for elements to be visible. `e/wait-has-text` and `e/wait-has-text-everywhere` can be used to wait until text is visible on the page.

`e/wait` should be used only when the other wait options don't make sense.

### Scrolling

An element may not be visible on the page. Use `e/scroll`

### Executing Javascript
Use js-execute to evaluate a Javascript code in the browser:

```clojure
(e/js-execute driver "alert('Hello from Etaoin!')")
(e/dismiss-alert driver)
```

### Interacting with the page:

The following functions are useful for interacting with the page, filling forms, etc:

- `e/fill`
- `e/fill-multi`
- `e/fill-human`
- `e/fill-human-multi`
- `e/click`
- `e/double-click`

## Screenshots

Take full screen screenshots with `e/screenshot`

```
(e/screenshot driver "target/etaoin-play/screens1/page.png")
```

Screenshot an element:

```
(e/screenshot-element driver {:tag :form :class :formy} "target/etaoin-play/screens3/form-element.png")
```

## Resources

- references/user-guide.adoc - the full user guide for etaoin
- resources/sample-repl-session.clj - a sample repl session
