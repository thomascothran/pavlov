# Model Checker Performance

Created: October 22, 2026

Commit: `77476d1` on `master` ("Speed up the model checker with an exact LTS
builder and shared witness paths")

## Summary

`check` is now 1.2–2.6× faster on large state spaces. When a bthread waits on
the events of many other bthreads (an observer), the LTS also takes 3–4× less
memory. Models with many deadlocks or safety violations get the largest gain,
from 9× to 135×, because finding their witness paths used to cost
violations × edges.

`check` returns exactly the same results as before: the same nodes, edges,
node ids, edge order, witness paths and violation categories.

## How it was done

### Measuring first

- `dev/bench/model_check.clj` (`bench.model-check`) is a benchmark suite of
  synthetic models in three tiers: smoke, standard and large. Between them
  they cover every result category: deadlocks, livelocks, liveness, safety,
  impossible events and truncation.
- The checker as it was before these changes is frozen in `dev/bench/legacy/`
  as `bench.legacy.*` and serves as the reference. An oracle compares the live
  checker with it. Its strict mode requires identical LTS and `check` output,
  and it requires deadlock and safety witnesses to be shortest paths.
- Profiling used clj-async-profiler, through `profile!`.

### Changes

1. **A dedicated BFS for building the LTS** (`graph/->lts`).
   - It deduplicates states when they are discovered rather than when they are
     dequeued.
   - Before stepping from a node, it restores only the bthreads that the
     event will wake.
   - It reads the blocked set from the state's `:blocks` index.
2. **`state/step` keeps the index entries of bthreads whose bid is unchanged.**
   A bthread that waits on many events, such as an observer or a logger, is
   woken by almost every event but often returns the same bid. Its entries in
   the event indexes used to be rebuilt on every step. Now they are kept
   (`notification/notify-step!` reports the unchanged bthreads). Consecutive
   states therefore share structure, which is where most of the memory
   saving comes from.
3. **No copies of index sets** when finding the bthreads to notify
   (`bthreads-to-notify`, `triggered-bthreads`).
4. **One shared shortest-path tree for witness paths** (`algo/shortest-path-tree`
   and `algo/tree-path`).
   - Before, `check` ran a separate BFS from the root for every deadlock and
     every safety violation, and rebuilt the adjacency map each time.
   - Now one BFS-parent tree is built, only when a witness is needed, and each
     path is read back from it.
   - The parent of each node is the one the old search would have used (the
     first node in BFS order, through the first edge in edge order), so the
     paths are identical.

### Tried and dropped

- **Slim node payloads.** These saved about 2% of the time.
- **Diffing changed bids.** This updates only the event types that differ
  between a bthread's old and new bid. It gave no measurable gain over keeping
  unchanged bids, and it added complexity to core.
- **Merging the remaining analyses into the search.** Livelock, liveness,
  deadlock, safety and impossible-event checks together take about 20% of
  `check`, so the most this could save is about 1.25×.

### Validation

- The oracle reports identical results on all 22 benchmarks in the standard
  tier.
- `check` gives the same output as the frozen copy on every standard benchmark
  for `:max-nodes` nil, 0–59, 100, 250 and 1000.
- A random-walk differential of `state/step` against the frozen copy found no
  differences.
- The JVM, integration, CLJS and Squint test suites pass.

## Results

The figures are medians from fresh JVMs: one warmup and three runs, on
OpenJDK 21 on a 32-core machine, single-threaded. `check` time includes
building the LTS. Memory is the retained size of the finished LTS.

### Measured, by graph size

| Model | Nodes | Edges | `check` ms, before → after | Speedup | LTS memory, before → after |
|---|---|---|---|---|---|
| safety-5x3 | 1,346 | 4,242 | 195 → 21 | **9.5×** | 1.6 → 1.4 MB |
| tree-10 (1,024 deadlocks) | 2,047 | 2,046 | 464 → 14 | **33×** | 1.5 → 1.4 MB |
| tree-12 (4,096 deadlocks) | 8,191 | 8,190 | 7,635 → 56 | **135×** | 6.0 → 5.5 MB |
| philosophers-6 | 10,250 | 32,807 | 387 → 328 | 1.2× | 24 → 21 MB |
| workers-5x4-last (stateful observer) | 16,486 | 57,925 | 873 → 366 | **2.4×** | 67 → 23 MB (2.9× less) |
| workers-6x4 | 34,961 | 124,176 | 818 → 649 | 1.3× | 46 → 37 MB |
| workers-6x4-count (observer) | 34,961 | 124,176 | 2,150 → 828 | **2.6×** | 181 → 52 MB (3.5× less) |
| philosophers-7 | 47,184 | 177,248 | 2,342 → 1,832 | 1.3× | 117 → 106 MB |
| workers-7x4 | 261,105 | 983,920 | 7,208 → 5,486 | 1.3× | 368 → 283 MB |
| workers-7x4-count (observer) | 261,105 | 983,920 | 18,777 → 7,496 | **2.5×** | 1,532 → 413 MB (3.7× less) |

Memory allocated while running `check` fell by the same proportions. On
workers-7x4-count it went from 59.7 GB to 19.3 GB, which also means less time
spent in garbage collection.

### Cost per node and per edge

| Model type | CPU per edge, before → after | LTS memory per node, before → after |
|---|---|---|
| Mostly independent bthreads | ~7.3 → ~5.5 µs (1.3×) | ~1.4 → ~1.1 KB |
| A bthread waits on everyone's events (observer) | ~15–19 → ~6.5–7.5 µs (2.5×) | ~4–6 → ~1.5 KB (3–4×) |
| Heavy blocking (philosophers) | ~13 → ~10 µs (1.3×) | ~2.5 → ~2.3 KB |
| Finding witness paths for V violations | ~0.22 µs × V × edges → close to 0 | — |

The witness-path cost was fitted on tree-10 and tree-12, which both give
0.22 µs per violation per edge.

### Estimates by size

These numbers are scaled linearly from the per-edge costs above, assuming
about 4 edges per node, as in the benchmarks.

| Graph size | CPU, independent | CPU, observer | Memory, independent | Memory, observer |
|---|---|---|---|---|
| 10k nodes / 40k edges | 0.29 → 0.22 s | 0.72 → 0.28 s | 14 → 11 MB | 60 → 16 MB |
| 100k nodes / 400k edges | 2.9 → 2.2 s | 7.2 → 2.8 s | 140 → 110 MB | 600 → 160 MB |
| 1M nodes / 4M edges | 29 → 22 s | 72 → 28 s | 1.4 → 1.1 GB | 6 → 1.6 GB |

Before these changes, a model with violations also paid 0.22 µs × V × E to
find its witness paths:

| Violations (V) | Edges (E) | Extra time before | Extra time now |
|---|---|---|---|
| 100 | 400k | ~9 s | close to 0 |
| 1,000 | 4M | ~15 min | close to 0 |

### Caveats

- **The models are synthetic.** The cost per edge grows with the number of
  bthreads and with the cost of their step functions, so real applications
  will have different constants.
- **Peak heap use is higher than LTS memory.** The figures above count only
  the finished graph, not garbage produced along the way.
- **The estimates assume a flat cost per edge.** In practice it may change as
  graphs grow.

## Where the remaining time goes

Almost all of `check` is now spent building the LTS. The cost is spread
thinly over `state/step`'s bookkeeping (persistent map and set updates, small
allocations, protocol dispatch), and no single hotspot is left. Large further
gains would have to come from:

- **Fewer states:** canonicalization, partial order reduction, or running
  observer bthreads as monitors outside the explored state. See
  [partial-order-reduction.md](partial-order-reduction.md).
- **Cheaper states:** a model-checking stepper that caches each bthread's
  response to an event.
- **Less elapsed time:** parallel exploration.

## Reproducing

```bash
clj -J-Xmx8g -M:dev -m bench.model-check oracle standard
clj -J-Xmx8g -M:dev -m bench.model-check benchmark standard <label>
```

For the large models, select them by id with a 12 GB heap. The `large` tier
also includes the smaller tiers and runs out of memory at 8 GB:

```bash
clj -J-Xmx12g -M:dev -e "(require 'bench.model-check) (in-ns 'bench.model-check) \
  (benchmark! [baseline current] [:workers-7x4 :workers-7x4-count :philosophers-7 :tree-12] \"large\") \
  (shutdown-agents)"
```

Results are written to `dev/bench/model-check/`, which is gitignored.
