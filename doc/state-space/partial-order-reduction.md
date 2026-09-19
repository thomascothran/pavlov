# Partial Order Reduction in Pavlov

Created: April 2, 2026

## Purpose

This note answers two questions:

1. Does partial order reduction (POR) help Pavlov model checking?
2. What is POR, explained using Pavlov's current LTS representation?

This discussion is **safety-first**. The core intuition below is about preserving safety/reachability results. Deadlock, livelock, and liveness preservation need extra conditions and should be treated as follow-on work.

## Short answer

### Does POR help Pavlov?

**Yes, sometimes substantially, but not always.**

Pavlov already does one important reduction: it hashes behavioral-program states and merges paths that reach the same state into one LTS node. That already removes duplicate **nodes**.

POR can still help because it removes duplicate **interleaving work before the merge happens**.

That benefit is real when:

- a state enables multiple events,
- different event orders are behaviorally equivalent for the property being checked, and
- those different orders eventually lead to the same **full hashed state**.

That benefit is smaller when:

- most enabled events genuinely lead to different future behavior,
- many bthreads react to most events, or
- safety bthreads care about event order, causing the states to remain distinct.

So the right Pavlov-specific answer is:

> POR is promising when Pavlov programs contain many order-only differences that converge back to the same hashed behavioral-program state. It is not a universal win, and its usefulness is constrained by the fact that safety bthreads are part of the state hash.

---

## The Pavlov LTS we already have

Today Pavlov explores a labeled transition system (LTS):

- **nodes** are hashed behavioral-program states
- **edges** are selected events

In the current implementation:

- `search/succ` generates all enabled successor events from a state
- `search/identifier` hashes the state
- `graph/->lts` and `search/bfs-reduce` merge states that have the same identifier
- `model.check/check` includes `:safety-bthreads`, `:environment-bthreads`, and normal `:bthreads` in the same explored program

So the state identity is already a **full BP state identity**, not just an application-level state.

That matters a lot:

> If two executions reach the same business state but different safety-bthread states, they are different Pavlov states.

That is the correct basis for thinking about POR in this repository.

---

## What Pavlov already gets for free: state merging

Suppose the graph contains this shape:

```text
     :a
  s -----> t
   \
    \
     -----> t
       :b
```

Pavlov already merges these two paths if both successors hash to the same state `t`.

This already happens today. For example, the graph tests include a case where `:a` and `:b` both leave the initial state and converge immediately to the same hashed node.

That is useful, but it is **not yet the full point of POR**.

State merging says:

> "If two paths reached the same state, keep one node."

POR says:

> "If two paths differ only by an irrelevant ordering choice, do not explore both orderings in the first place."

---

## What POR is

POR is a way to avoid exploring multiple traces that differ only by the order of **independent** events.

In an LTS, imagine a state `s` where both `:a` and `:b` are enabled.

If both orders are possible, and if swapping them does not matter, then we get a diamond:

```text
        :a          :b
     s -----> s1 ------> t
     |
     |
     v
    s2 ------> t
        :b          :a
```

The two traces are:

- `[:a :b]`
- `[:b :a]`

If they lead to the same full hashed state `t`, then for safety checking they may be redundant. POR tries to keep only one representative ordering.

So, in Pavlov terms:

> POR is not about merging equal nodes after exploration. It is about shrinking the set of outgoing edges we choose to explore from a state, while still preserving the safety-relevant reachable states.

---

## The key Pavlov idea: independence must be defined over the full hashed state

In many concurrency discussions, people talk loosely about two actions being "independent" because they belong to different tasks or threads.

That is **too weak** for Pavlov.

In Pavlov, an event is broadcast to all relevant bthreads, and safety bthreads are part of the explored system state. So two events should be treated as independent only if, with respect to the **full state that Pavlov hashes**, they do not meaningfully interfere.

For intuition, a strong Pavlov-flavored notion is:

Two enabled events `e1` and `e2` are independent at state `s` only if:

1. taking `e1` does not prevent `e2` from still being taken later,
2. taking `e2` does not prevent `e1` from still being taken later, and
3. `e1; e2` and `e2; e1` lead to the same full hashed BP state.

Condition 3 is the important one for this repository.

Because the hash includes bthread state and bids, and because safety bthreads are included in the program, **order-sensitive monitors make events dependent**.

---

## Example 1: immediate convergence

This is the simplest familiar case.

```text
     :a
  s -----> t
   \
    \
     -----> t
       :b
```

Interpretation:

- from `s`, both `:a` and `:b` are selectable
- after either event, the bprogram reaches the same hashed state `t`

Pavlov already collapses `t` into one node.

This example is useful because it shows that:

- multiple event choices can already converge in today's LTS, and
- state identity is separate from path history

But this is still only a warm-up. The more characteristic POR example is the next one.

---

## Example 2: the actual POR shape — a diamond

Consider a bthread that wants both `:a` and `:b` to happen, but does not care about their order.

Its LTS shape can look like this:

```text
        :a          :b
     s -----> s1 ------> t
     |
     |
     v
    s2 ------> t
        :b          :a
```

with a final `:done` edge after `t`.

Here:

- `s1` means "we have seen `:a` but not `:b` yet"
- `s2` means "we have seen `:b` but not `:a` yet"
- `t` means "we have seen both"

The important fact is that both paths:

- `[:a :b]`
- `[:b :a]`

reach the same hashed state `t`.

Without POR, Pavlov explores both arms of the diamond and then merges them at `t`.

With POR, if `:a` and `:b` are independent for the safety property being checked, the checker can explore just one representative ordering and still reach `t`.

This is the main benefit of POR in Pavlov:

> it cuts away duplicate ordering work that the current hash-based merge only removes later.

---

## Example 3: why safety bthreads matter so much

Now add a safety bthread that records whether `:a` happened before `:b` or vice versa.

The application-level story may still look order-insensitive:

- both events happened
- the main workflow is ready to continue

But the **full behavioral-program state** is now different:

- after `[:a :b]`, the safety bthread says "a-before-b"
- after `[:b :a]`, the safety bthread says "b-before-a"

So the diamond no longer converges to one hashed node:

```text
        :a          :b
     s -----> s1 ------> t-ab
     |
     |
     v
    s2 ------> t-ba
        :b          :a
```

Now POR must **not** treat those two orders as equivalent, because Pavlov itself says they are different states.

This is the most important Pavlov-specific constraint on POR.

> If a safety bthread distinguishes the order, then the order is behaviorally relevant, and POR should not collapse it.

---

## So when does POR buy us something in Pavlov?

POR helps when the state space contains many diamonds of the form:

- both events are enabled now,
- both orders stay possible,
- both orders reach the same full hashed state,
- and no safety property cares which order occurred.

Typical good cases:

- two events update disjoint parts of behavior,
- a coordinator only cares that both happened, not which came first,
- safety bthreads only care about reachability of bad states, not the order between these two events.

Typical poor cases:

- the second event becomes blocked depending on the first,
- a bthread changes its bid in an order-sensitive way,
- a safety bthread records or reacts to ordering,
- many events wake many of the same bthreads, making commutativity rare.

So the answer is neither "POR obviously helps" nor "POR is useless because we already hash states."

The right answer is:

> Hashing and POR solve different parts of the problem. Hashing merges equivalent states after they are reached. POR tries to avoid exploring redundant orderings on the way there.

---

## A concise safety-first conclusion

For Pavlov contributors, the most useful mental model is:

1. We already have a state-based LTS.
2. We already merge equal states by hash.
3. POR would go one step earlier and prune redundant event-order explorations.
4. The correctness question is not "same application state?" but "same full hashed BP state, including safety bthreads?"

That leads to the main conclusion:

> Partial order reduction can benefit Pavlov model checking, but only when different event orderings are genuinely equivalent in the full hashed behavioral-program state. Because safety bthreads are part of that state, they sharply limit when two paths are actually reducible.

## Caveat: beyond safety

Everything above is the right intuition for **safety/reachability**.

For deadlocks, livelocks, and liveness, classic POR needs additional provisos. In particular, cycle-sensitive properties are easier to get wrong than plain bad-state reachability. So the safe framing for Pavlov is:

- POR is easiest to justify first for safety checking
- extending it to the rest of `model.check` needs extra theory and extra care

That does not make POR a bad fit. It just means the safety story should come first.
