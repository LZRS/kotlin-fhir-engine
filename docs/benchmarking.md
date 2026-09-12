# Benchmarking

This covers the micro benchmarks that live in `:engine`. They measure what sits underneath the
engine's public surface — pure-CPU functions, and SQLite itself — where the cost of an algorithm or
an index is the whole story.

```bash
./gradlew :engine:benchmark        # everything
./gradlew :engine:indexBenchmark   # just the index and tuning sweeps, about a minute
```

## Micro benchmarks

They live in `:engine` rather than in a module of their own, in a `benchmark` compilation associated
with `main`. That association is the reason for the placement: it grants access to the engine's
`internal` declarations, the same way test compilations do. Sources are in
`engine/src/desktopBenchmark/kotlin/`.

Desktop/JVM only. kotlinx-benchmark backs the JVM target with JMH, which forks, warms and reports a
confidence interval, so these land near ±0.3%. Its Kotlin/JS support targets Node while this
project's web targets are browser-configured, and a native target would need a `macosArm64` the
engine does not build.

| Class | What it measures |
|---|---|
| `ResourceIndexerBenchmark` | `ResourceIndexer.index()` — a FHIRPath evaluation per search parameter, on every write |
| `JsonDiffBenchmark` | `JsonDiff.diff()` — the hand-written RFC 6902 replacement for Jackson + jsonpatch |
| `ResourceSerializerBenchmark` | The serialize/deserialize floor under every read and write |
| `SearchQueryBenchmark` | `Search.getQuery()` — per-query cost, independent of how much is stored |
| `MoreResourcesBenchmark` | `getResourceClass`, `updateMeta`, `withId` — per-resource helpers |
| `PatchOrderingBenchmark` | Tarjan's over the pending-upload graph, the only cost that grows with queue length |
| `DateIndexShapeBenchmark` | Date index column order, swept from 1,000 to 50,000 rows |
| `StringIndexCollationBenchmark` | String index collation, swept the same way |
| `SqliteTuningBenchmark` | `ANALYZE`, `journal_mode` and `synchronous`, which the engine never sets |

The index sweeps write rows straight into the index tables rather than through `FhirEngine`, because
the question is what an index costs, not what indexing costs. That is also what makes 50,000 rows
affordable: the engine path spends about 200 us of FHIRPath per resource and would turn seconds of
setup into twenty minutes of it.

Every index trial asserts its query still selects the slice it was designed to — see
[Selectivity decides everything](#selectivity-decides-everything) — and the collation sweep also
asserts its two arms produce *different* query plans, because an index created over a NOCASE column
is NOCASE whatever the arm intended, and two identical arms compare cheerfully to zero difference.

### What the sweeps say today

`StringIndexCollationBenchmark`, us/op: binary 65.7, 534.2, 3139.9 at 1k/10k/50k rows against nocase
22.8, 31.9, 78.9 — the first scales with the table and the second barely moves, which is the whole
difference between a scan and a seek. **39.8x at 50,000 rows.**

`DateIndexShapeBenchmark`: a flat 11-13% for the reordered index at every size, against a selective
window. Not enough to pay for the extra index and the write cost it brings; see
[The two remaining shortfalls](#the-two-remaining-shortfalls).

`SqliteTuningBenchmark`: **nothing here is worth adopting.** `ANALYZE` leaves the prefix search at
43.3 to 45.8 us/op, i.e. no better and possibly slightly worse. `journal_mode=WAL` and
`synchronous=NORMAL` leave an indexed write at 14.1 ms against 15.5 and 15.9 ms — no gain, and the
WAL arm was wildly variable (+/-10.2 ms). One caveat worth keeping: this write batches 500 rows into
a single transaction, which is where WAL has least to offer. An engine doing many small
transactions might answer differently, and that is the version worth measuring before concluding
the engine should never set a PRAGMA.

## Index usage

`SearchQueryPlanTest` (in `:engine`'s `desktopTest`) runs `EXPLAIN QUERY PLAN` over each search shape
and asserts which SQLite index it uses.

```bash
./gradlew :engine:desktopTest --tests "*SearchQueryPlanTest*"
```

Not a benchmark, deliberately. A lost index only becomes visible in a timing run at a large corpus,
and a warm page cache hides it even then. The plan reports it in about a second, from an empty
database. Timing answers *how slow*; the plan answers *why*.

| Search | Index columns narrowed | Covering |
|---|---|---|
| token | all three | yes |
| reference, uri | all three | no |
| number, quantity | all three, range on the value | no |
| string prefix, contains | all three, as a range | no |
| **string `:exact`** | **two — `index_value` unused** | no |
| **date, dateTime** | **two — the range columns unused** | yes |

Sorting is never index-backed: every sorted search builds two temporary B-trees.

`StringSearchMatchingTest`, alongside it, runs real searches against a real database and asserts
which rows come back. `SearchTest` only compares generated SQL, so without it the collation could be
changed in either direction and every test would still pass while search quietly returned the wrong
rows.

### The two remaining shortfalls

Both are pinned as tests that name them.

**`:exact` string search** compares `COLLATE BINARY` against a NOCASE index, and SQLite will not use
an index whose collation differs from the comparison's. This is the deliberate price of making the
common prefix search fast: the two cannot both be indexed without a second, BINARY-collated column
mirroring `index_value`, which is disk and write cost for the rarer path.

**Date and dateTime** indices are `(resourceType, index_name, resourceUuid, index_from, index_to)`.
A range predicate can only use the column immediately after the equality prefix, and `resourceUuid`
sits in between, so neither comparator family can range. Moving it last and adding a second index
leading with `index_to` makes both usable, and measured **worse** end to end — about 13% on both a
birth-date range search and a delete, interleaved, n=3. `DateIndexShapeBenchmark` sweeps the same
change from 1,000 to 50,000 rows against a *selective* window and finds a flat ~13% gain instead.
The two do not contradict each other — they are different selectivities. Left as it is until
something measures a case that wants it.

### Selectivity decides everything

An index can only pay for itself when the predicate rejects most rows. That makes selectivity a
property of the **dataset**, not only the query, and getting it wrong disables a whole suite
silently.

This was learned the hard way. An end-to-end suite ran for a long time against a corpus with eight
given names and eight family names, so `family = "Smith"` matched one patient in eight, and a
birth-date range asked for thirty years of a seventy-year spread — over 40%. At those fractions no
index can help, so the search workloads could not tell a working index from a missing one. The
prefix-search fix above measured as *no change at all* against that dataset and was nearly discarded
on the strength of it; against a corpus with 676 names the same change is 3.4x to 4.5x end to end,
and 38x in isolation.

This is why every index benchmark here calls `assertSelectivity` in its setup: a trial whose
predicate stops matching the slice it was designed for fails loudly instead of quietly measuring row
fetching. If you add one, check what fraction of the table it matches. Anything above a few percent
is not measuring indexing.

### Reading these plans honestly

A query plan tells you *why* something is slow. It does not establish that a better-looking plan is
faster; that depends on size and selectivity, and here it twice was not.

Benchmarks on a developer machine need care to mean anything. During this work several single-run
comparisons produced double-digit "effects" that vanished under repetition — including one on a
read-only workload that no index change could possibly touch. What worked:

- **Interleave the arms.** Run A, B, A, B, not all of A then all of B; machine state drifts.
- **At least three repetitions per arm**, and compare the spread, not just the medians. Overlapping
  ranges are not a result.
- **Keep a control group** — measurements the change cannot affect. Their spread is the noise floor.
- Ignore relative deltas on anything below a few milliseconds.
- Run nothing else on the machine while a sweep is going. A Gradle build counts. So does a rebase.

The JMH benchmarks here need none of this discipline themselves: they fork, warm and report a
confidence interval. The discipline is for end-to-end comparisons run by hand.

## A failing benchmark does not fail the build

kotlinx-benchmark 0.5.0 builds its JMH `Runner` with `shouldFailOnError` left at JMH's default of
`false`, and exposes no setting to change it. A benchmark whose `@Setup` throws is therefore
reported as `<failure>` in the console, **omitted entirely from the JSON report**, and the process
still exits 0. The report has no failure or error field, so a run that lost three of twenty
benchmarks is indistinguishable from one that was only ever configured to run seventeen.

This is not hypothetical. `StringIndexCollationBenchmark` asserts that its two arms produce
different query plans; run it against an engine without the NOCASE column and three of its six
combinations abort in setup. Gradle reported `BUILD SUCCESSFUL`.

`engine/build.gradle.kts` therefore watches the runner's own output and fails the task when a
failure marker appears. If these benchmarks are ever added to CI, that check is what makes a broken
benchmark visible; without it the job goes green.
