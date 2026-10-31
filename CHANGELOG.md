# Changelog

Versions are `MAJOR.MINOR.COMMITS`, where `COMMITS` is the repository's
commit count at release time. All modules share the same version.

## 5.0 (unreleased)

### Breaking change: a request is satisfied only by an equal event

Previously, selecting an event notified every bthread that had requested *any
event of the same type*. If Alice requested
`{:type :order-food :entree :steak}` and Bob requested
`{:type :order-food :entree :vegan-salad}`, selecting the steak advanced both
of them, and Bob's salad was never ordered.

Now a bthread is notified for a request only when the selected event is `=` to
the event it requested. Waits and blocks are unchanged: they still match every
event of the type.

| Bid entry  | Matches the selected event when...               |
|------------|--------------------------------------------------|
| `:request` | the selected event is `=` to the requested event |
| `:wait-on` | the selected event has the same `event/type`     |
| `:block`   | the selected event has the same `event/type`     |

#### What can break

1. **`:x` and `{:type :x}` are now different events.** A bthread that requests
   `:x` is not advanced by `{:type :x}`, and vice versa. Use one form
   consistently, both in requests and in `submit-event!`/subscriber events.

2. **Relying on "any event of my type" through a request.** Code that requested
   one event but expected to be advanced by a *different* event of the same
   type will now stay parked. If that is the intent, add the type to
   `:wait-on`:

   ```clojure
   ;; before: advanced by any :order/placed event
   {:request #{{:type :order/placed :order-id 42}}}

   ;; after: same behavior, stated explicitly
   {:request #{{:type :order/placed :order-id 42}}
    :wait-on #{:order/placed}}
   ```

3. **Model-checking results can change.** Models in which several bthreads
   request same-type events with different data now explore every
   interleaving. Before, selecting one of those requests also satisfied the
   others, which hid states. Expect more states, and possibly new deadlocks
   or violations that were previously masked.

4. **Event implementations need consistent `=` and `hash`.** Custom `Event`
   types (records, deftypes, reified objects) are matched with `=` (and
   `hash` once the request index grows). Requested events must be hashable.
   On Squint, `=` is structural, so two deftype/reify instances without their
   own equality are equal if their fields are.

5. **Inspection data changed shape.** The `:requests` index in program state,
   which appears in model-checker node payloads and witness states and in
   `subscribers/tap` output, is now keyed by the requested event instead of
   by its type (still by type on Squint). Waits and blocks are still keyed by
   type.

#### Not affected

- Event selection, priorities and blocking.
- Programs where every requested event of a given type is the same value
  (e.g. all keyword events): they behave as before.
- Performance: scheduler and model-checker benchmarks are within noise of the
  previous release.
