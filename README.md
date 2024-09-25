# Pavlov: Behavioral Programming for Clojure

*Status: pre-release. Don't use.*

Pavlov is a behavioral programming library for Clojure(Script).

Behavioral programming is an event-driven programming paradigm that emphasizes independently defining behaviors and composing them together.

To understand behavioral programming, the following resources are useful:

- [Behavioral Programming](https://cacm.acm.org/research/behavioral-programming/#R26), by David Harel, Assaf Marron, and Gera Weiss (2012)
- [The Behavioral Programming Web Page](https://www.wisdom.weizmann.ac.il/~bprogram/more.html)
- [Programming Coordinated Behavior in Java](https://www.wisdom.weizmann.ac.il/~/bprogram/pres/BPJ%20Introduction.pdf) by David Harel, Assaf Marron, and Gera Weiss.
- [Documentation and Examples for BPJ](https://wiki.weizmann.ac.il/bp/index.php/User_Guide)

## Example

```clojure
(ns water-controls.app
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram :as bp]))

(def water-app
  (let [add-hot  (b/seq (repeat 3 {:request #{:add-hot-water}}))
        add-cold (b/seq (repeat 3 {:request #{:add-cold-water}}))
        alt-temp (b/seq 
                    (interleave
                       (repeat {:wait-on #{:add-cold-water}
                                :block #{:add-hot-water}})
                       (repeat {:wait-on #{:add-hot-water}
                                :block #{:add-cold-water}})))]
    (bp/make-program [add-hot add-cold alt-temp]))
```

## Roadmap

1. Implement canonical examples in the test suite
2. Firm up APIs
3. ClojureScript support
4. Opt-in parallelization
5. Documentation

## License

Copyright © 2024 Thomas Cothran

Distributed under the Eclipse Public License version 1.0.
