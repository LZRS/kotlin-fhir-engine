# Benchmarking

The micro benchmarks in `:engine` measure what sits underneath the engine's public surface:
pure-CPU functions, and SQLite itself.

```bash
./gradlew :engine:prBenchmark :engine:prNoisyBenchmark   # what CI runs on a pull request
./gradlew :engine:indexBenchmark   # the sweeps; about ninety forked trials, tens of minutes
./gradlew :engine:benchmark        # everything at full size; what CI runs on a push to main
```

## Micro benchmarks

Sources are in `engine/src/desktopBenchmark/kotlin/`, in a `benchmark` compilation associated with
`main`. The association grants access to the engine's `internal` declarations, the same way test
compilations do.

Desktop/JVM only. kotlinx-benchmark backs the JVM target with JMH, which forks, warms and reports a
confidence interval. Its Kotlin/JS support targets Node while this project's web targets are
browser-configured, and a native target would need a `macosArm64` the engine does not build.

| Class                            | What it measures                                                                        |
|----------------------------------|-----------------------------------------------------------------------------------------|
| `ResourceIndexerBenchmark`       | `ResourceIndexer.index()` — a FHIRPath evaluation per search parameter, on every write  |
| `JsonDiffBenchmark`              | `JsonDiff.diff()`, the hand-written RFC 6902 replacement for Jackson + jsonpatch        |
| `ResourceSerializerBenchmark`    | The serialize/deserialize floor under every read and write                              |
| `SearchQueryBenchmark`           | `Search.getQuery()` and `XFhirQueryTranslator` — per-query cost, independent of how much is stored |
| `SearchExecutionBenchmark`       | A search end to end: the query, the rows, a parse per row, and `_include`/`_revinclude` |
| `SearchResultSizeBenchmark`      | The same, swept by how many resources come back rather than by corpus size |
| `UcumCanonicalBenchmark`         | Canonicalizing a quantity, which every indexed value and every filter pays              |
| `MoreResourcesBenchmark`         | `getResourceClass`, `updateMeta`, `withId` — per-resource helpers                       |
| `PatchOrderingBenchmark`         | Tarjan's over the pending-upload graph, the only cost that grows with queue length      |
| `DateIndexShapeBenchmark`        | Date index column order, swept from 1,000 to 50,000 rows                                |
| `StringIndexCollationBenchmark`  | String index collation, swept the same way                                              |
| `QuantityIndexShapeBenchmark`    | Whether the quantity index should carry the unit before the value                       |
| `LookupIndexCoveringBenchmark`   | Whether the reference and uri indices should carry `resourceUuid`, as the token one does |
| `SortBenchmark`                  | What sorting costs, and whether paging escapes it                                       |
| `ResourceInsertBenchmark`, `ResourceUpdateBenchmark`, `ResourceDeleteBenchmark`, `ResourceReadBenchmark` | The CRUD paths through the real `ResourceDao` and schema |
| `EngineCreateBenchmark`, `EngineUpdateBenchmark`, `EngineDeleteBenchmark` | The same writes through `DatabaseImpl`: one transaction, and a local change per resource |
| `BulkImportBenchmark`            | A download page written in one transaction, with no local change recorded               |
| `LocalChangeReadBenchmark`       | Reading the pending queue and its references, which is where an upload starts           |
| `UploadAssemblyBenchmark`        | Squashing pending changes into patches, and patches into upload requests                |
| `EngineStartupBenchmark`, `DatabaseOpenBenchmark` | What a launch pays before the first read              |
| `PayloadRepresentationBenchmark` | Storing `serializedResource` as JSON text against the same resources as a protobuf blob |
| `SqliteTuningBenchmark`          | `ANALYZE`, `journal_mode` and `synchronous`, which the engine never sets                |

`indexBenchmark` covers more than its name suggests: the index shapes, `SqliteTuningBenchmark`,
`PayloadRepresentationBenchmark`, and the four CRUD classes the sweeps are read against. They are
grouped because each carries a `@Param` grid, which is also why the pull-request tier leaves them
out.

The index sweeps write rows straight into the index tables rather than through `FhirEngine`,
because the question is what an index costs, not what indexing costs. It is also what keeps the
largest sweep affordable: the engine path spends about 200 us of FHIRPath per resource, which would
turn seconds of setup into minutes.

Every index trial asserts its query still selects the slice it was designed for — see
[Selectivity](#selectivity) — and the collation sweep also asserts its two arms produce different
query plans. An index created over a column inherits that column's collation whatever the arm
intended, so two identical arms would otherwise compare to zero difference.

### Current results

`StringIndexCollationBenchmark`, us/op: binary 65.7, 534.2, 3139.9 at 1k/10k/50k rows against
nocase 22.8, 31.9, 78.9. The first scales with the table, the second barely moves — the difference
between a scan and a seek, 39.8x at the largest size. The engine ships the `binary` arm; see
[Known shortfalls](#known-shortfalls).

`DateIndexShapeBenchmark`: a flat 11-13% for the reordered index at every size, against a selective
window. Not enough to pay for the extra index and the write cost it brings; see
[Known shortfalls](#known-shortfalls).

`QuantityIndexShapeBenchmark`, us/op at 1k/10k/50k rows. A search naming a unit: `current` 100.8,
420.0, 1917.9 against `codeFirst` 104.1, 262.3, 1256.8 — a 34% gain at the top size. The same search
without a unit reverses it: `current` 155.5, 1053.2, 4701.3 against `codeFirst` 340.0, 2644.2,
12188.8, which is 2.6x worse. Keeping both indices takes the gain without the loss (91.0, 297.5,
1244.8 with a unit; 135.3, 1097.1, 4883.4 without) at the cost of a second index on every quantity
write, which is not measured here. See [Known shortfalls](#known-shortfalls).

`LookupIndexCoveringBenchmark`: appending `resourceUuid` is worth about 25% on a reference lookup
and 21% on a uri lookup at 50,000 rows (5320 to 3948 us/op, and 5359 to 4215). At 1,000 rows the
arms are indistinguishable — the whole index is cached, so saving a row fetch saves nothing. The
gap at 50,000 rows is only just outside the combined error, so treat the size rather than the
direction as provisional.

`SortBenchmark` is the largest single effect measured here, and it is about paging rather than
about an index. An unsorted first page is flat in the size of the corpus — 87.7, 107.4, 79.3 us/op
at 1k/10k/50k, because `LIMIT` stops as soon as it has fifty rows. A sorted first page is linear —
1320, 10593, 48886 us/op — because nothing arrives in order, so the whole corpus is ordered before
fifty rows come back. At 50,000 rows a sorted page costs **617x** an unsorted one, and that ratio
grows with the table.

Sorting an already-filtered slice is nearly free by comparison: 6829 against 7163 us/op at 50,000
rows, about 5%. The cost is in ordering a corpus, not in the ordering itself. See
[Sorting](#sorting).

`SqliteTuningBenchmark`: nothing here is worth adopting. On an idle machine all four arms agree to
within 1% on both write shapes — a batched insert is 5.61 ms against 5.67 for WAL, and one
transaction per row 2.163 ms against 2.172 — with the `analyze` arm, which cannot affect a write at
all, differing from `default` by 0.16%. That is the noise floor, well below any difference between
the arms. `ANALYZE` does nothing for the prefix search either (45.0 against 46.3 us/op).

One caveat does not transfer: this is macOS, where SQLite's default `fsync` does not force a full
disk barrier. Journal mode is largely about what a commit must durably record, so a platform with
stricter durability — Android on real storage — could answer differently. The conclusion is "no
effect on desktop", not "no effect".

`SearchExecutionBenchmark`, us/op over 1,000 patients and 1,000 observations: a string `:exact`
27.1, a reference lookup 36.5, a token filter 62.6, a number range 64.7, a count 73.4, a string
prefix 81.6, `:contains` 87.7, an unfiltered first page of fifty 148.0, `_revinclude` 122.5 — and
`_include` 2229.1.

That last one is the outlier worth chasing. `_include` costs 36x the same filter without it, while
`_revinclude` over the same corpus costs 1.5x, and the query plan says why. The `_include` join
reads `re.resourceType||'/'||re.resourceId = rie.index_value`, and an expression on the indexed side
cannot be a seek, so neither side of the join uses its index: SQLite walks every reference row of
the parameter and, for each, every resource of the included type. The cost is the product of the two
tables rather than the size of the result. `_revinclude` builds the same `type/id` strings in Kotlin
and binds them, so both sides seek — which is also the shape of the fix.
`SearchQueryPlanTest` pins both plans.

`SearchResultSizeBenchmark`, us/op over a fixed 10,000-patient corpus, varying only the page size:

| page | `page` | `pageWithInclude` | `pageWithRevInclude` |
|-----:|-------:|------------------:|---------------------:|
| 1 | 29.8 | 67.4 | 54.7 |
| 100 | 323.8 | 5,914.4 | 2,183.1 |
| 1,000 | 3,018.3 | 21,490.9 | 153,720.5 |
| 10,000 | 33,823.6 | — | 14,320,242.4 |

A plain page is linear and cheap: about 3.0 us per resource returned, over a fixed 27 us of query.
That is the whole cost of an unfiltered search, and it matches what the on-device run over 20,000
patients implies — counting them took 2.75 ms, sorting them about 836 ms, and returning them about
8,090 ms, or 0.40 ms each on a Tab A9.

The include columns are not linear. `pageWithRevInclude` grows 70x for the first ten-fold step and
93x for the second: a `_revinclude` over a page of ten thousand takes **14.3 seconds** where the
page alone takes 34 ms. `Search.execute` builds its result by rescanning the whole resolved list
once per base resource, so the work is the product of the two — and on the revInclude side the
`type/id` key is rebuilt inside that scan, which is why it is two orders of magnitude worse than
the include side doing the same thing with a uuid comparison. The include column is quadratic too,
with a small enough constant that at these sizes it is still dominated by its join; `—` is not
measured, because the arm is slow enough at 10,000 to be worth skipping until the join is fixed.

This is the one shortfall here that needs no schema change and no trade: group the resolved
resources once, by key, instead of once per base resource.

`EngineCreateBenchmark` against `ResourceInsertBenchmark`: writing fifty patients costs 13.5 ms
through `DatabaseImpl` and 104.1 ms through `ResourceDao` a resource at a time — 270 us against
2,083 us each. Both arms are noisy, at 9% and 4% error, and the gap is far wider than that spread. The engine path does strictly more work per resource, the local-change ledger
included, and still wins by eight times, because it commits once for the batch where the DAO path
commits once per resource. `BulkImportBenchmark` writes 500 in one transaction at 305 us each,
without a ledger, which bounds what the ledger can be costing.

Each seeded patient carries two references, so an update pays `LocalChangeDao` to diff them: adding
those references moved `EngineUpdateBenchmark` from 14.2 ms to 15.4 ms for fifty resources, about
8%. Re-indexing dominates either way.

`UploadAssemblyBenchmark` at fifty resources, each with an insert and two updates: squashing into
one patch per resource takes 253.2 us against 2.9 us for keeping every change, because squashing
replays each RFC 6902 payload over the one before it. Turning the result into requests costs 73.4 us
as bundles and 71.9 us as individual URLs.

`DatabaseOpenBenchmark`: 1.79 ms to create a schema on a fresh directory, 0.49 ms to reopen one that
already holds 500 patients. `EngineStartupBenchmark` reports nanoseconds for constructing the
provider and the indexer, which is the useful answer — the FHIRPath engine and the R4 parameter
tables are built once for the process, so the cost lands in warmup and no repeated-invocation
harness can see it. `coldIndexFirstResource` runs level with `indexRichPatient` for the same reason.

`PayloadRepresentationBenchmark`: a binary payload is half the bytes and about 14% of the read.
Storing 20,000 mixed resources takes 7.19 MB as JSON text against 3.51 MB as a protobuf blob, and
fetching two hundred of them is 125 us against 44 — a 65% saving on the I/O.

The end-to-end gain is smaller than the size reduction because parsing dominates and does not
change: decoding those payloads costs 443 us as JSON and 435 us as protobuf, a difference inside
the error bars. The two halves add up — 125 + 443 against a measured 565, and 44 + 435 against a
measured 485 — which confirms the benchmark measures what it claims.

The trade is roughly 14% on reads that touch many rows, 10% on a point read, 14% on a write, and
half the disk, against a destructive schema migration and a wire format that is not
self-describing.

## Index usage

`SearchQueryPlanTest` (in `:engine`'s `desktopTest`) runs `EXPLAIN QUERY PLAN` over each search
shape and asserts which SQLite index it uses.

```bash
./gradlew :engine:desktopTest --tests "*SearchQueryPlanTest*"
```

Not a benchmark, deliberately. A lost index only becomes visible in a timing run at a large corpus,
and a warm page cache hides it even then. The plan reports it in about a second, from an empty
database. Timing answers how slow; the plan answers why.

| Search                  | Index columns narrowed             | Covering |
|-------------------------|------------------------------------|----------|
| token                   | all three                          | yes      |
| reference, uri          | all three                          | no       |
| number                  | all three, range on the value      | no       |
| quantity, no unit       | all three, range on the value      | no       |
| **quantity with a unit**| **two and the range — `index_code` unused** | no |
| string `:exact`         | all three                          | no       |
| string prefix, contains | two — `index_value` unused         | no       |
| date, dateTime          | two — the range columns unused     | yes      |
| `_revinclude`           | all three, then the resource by uuid | no     |
| **`_include`**          | **two — the join compares a concatenation, so neither side seeks** | no |

Reference and uri are the only lookups that are not covering; the token index carries
`resourceUuid` and theirs do not.

`_include` is the worst plan of the set, and the only one whose cost grows with the product of two
tables. `_revinclude` runs the same work as two seeks, so the shapes of both the problem and the fix
are already in the codebase.

Sorting is never index-backed: every sorted search builds two temporary B-trees.

`StringSearchMatchingTest`, alongside it, runs real searches against a real database and asserts
which rows come back. `SearchTest` only compares generated SQL, so without it the collation could
be changed in either direction and every test would still pass while search returned the wrong
rows.

### Known shortfalls

Each is pinned as a test that names it.

**Prefix string search** compiles to `index_value LIKE ? || '%' COLLATE NOCASE` and narrows on
`(resourceType, index_name)` alone. Two things block it, and fixing either alone changes nothing:
SQLite applies its LIKE optimisation only when the pattern is a literal or a plain parameter, and
it uses an index only when the index collation matches the comparison's. Fixing both is worth 39.8x
on the largest sweep. Adopting it would cost `:exact` its index — the two cannot both be indexed
without a second column mirroring `index_value` — and a collation change needs a schema version
bump.

**Date and dateTime** indices are `(resourceType, index_name, resourceUuid, index_from, index_to)`.
A range predicate can only use the column immediately after the equality prefix, and `resourceUuid`
sits in between, so neither comparator family can range. Moving it last and adding a second index
leading with `index_to` makes both usable, and measured worse end to end — about 13% on both a
birth-date range search and a delete, interleaved, n=3. `DateIndexShapeBenchmark` sweeps the same
change against a selective window and finds a flat ~13% gain instead. The two do not contradict
each other; they are different selectivities. Left as it is until something measures a case that
wants it.

**Quantity with a unit** is the same defect with a clearer answer. The index is
`(resourceType, index_name, index_value, index_code)`, and a search naming a unit emits
`index_system = ? AND index_code = ? AND index_value >= ? AND index_value < ?` — two equality
predicates and a range. The range column sits in front of `index_code`, and nothing after a range
is reachable, so the unit is ignored and the scan spans every unit recorded for the parameter.
Swapping the two is worth 34%, but costs 2.6x on a search that omits the unit, which then has a gap
where the unit would be. Keeping both indices takes the gain without the loss and is the shape to
adopt if this is picked up; what is unmeasured is the second index's cost on writes.

**`_include` cannot use an index at all**, and it is the largest of these by a wide margin. Its
join reads `re.resourceType||'/'||re.resourceId = rie.index_value`: a concatenation on the indexed
side, which SQLite cannot seek. Neither half of the join narrows — the reference rows are walked at
`(resourceType, index_name)` and the resources at `resourceType` alone — so the work is the product
of the two tables rather than the size of the result. `SearchExecutionBenchmark` measures a token
filter with an `_include` at 2,229 us against 62.6 us without it, at only 1,000 patients and 1,000
observations, and the gap widens with the corpus. `_revinclude` already does the same work as two
seeks by building the `type/id` strings in Kotlin and binding them, so the fix is to do that on the
include side too. This is the one shortfall here that needs no schema change and no trade.

**Reference and uri lookups are not covering.** Every filter subquery selects `resourceUuid` alone,
so an index ending in that column answers it without touching a row. `TokenIndexEntity` is
`(resourceType, index_name, index_value, resourceUuid)` and its plan says COVERING INDEX; the
reference and uri indices stop at `index_value`. Appending the column is worth about 20-25% at
50,000 rows and nothing at 1,000. It is the cheapest of the three to adopt, and reference lookups
carry the chained, `has` and `revInclude` searches, so it applies more often than a plain reference
filter suggests.

### Sorting

Not an index question, and the largest effect in the suite.

`Search.sort` compiles to a LEFT JOIN onto the index table, a GROUP BY to collapse resources with
several indexed values, and an ORDER BY. The join uses `(resourceUuid, index_name, index_value)` as
a covering index, so the lookup is not the problem; SQLite answers the GROUP BY and the ORDER BY
with a temporary B-tree each.

The consequence is that **`count`/`from` does not bound the work on a sorted search**. A LIMIT can
stop early only once rows arrive in order, and they do not, so a sorted first page orders the whole
corpus before returning fifty rows — 617x an unsorted page at 50,000 rows, growing with the table.
Paging through a sorted list pays that on every page.

Filtering first largely removes it: sorting a 1/676 slice costs about 5%. So the shape to avoid is
a sorted search with no filter, or with a weak one, which is also the shape a "browse all patients,
alphabetically" screen produces.

Nothing is proposed here yet. An index on `ResourceEntity(resourceType, resourceUuid)` might remove
the GROUP BY B-tree, and the `HAVING MIN(...) >= <sentinel>` the sort emits is worth a second look
before any of that — for a string sort it compares text against an integer, which SQLite always
resolves one way.

### Selectivity

An index can only pay for itself when the predicate rejects most rows. Selectivity is therefore a
property of the dataset, not only of the query, and getting it wrong disables a whole suite
silently.

The end-to-end benchmark suite outside this module ran for a long time against a generated corpus
of eight given names and eight family names, so `family = "Smith"` matched one patient in eight,
and a birth-date range asked for thirty years of a seventy-year spread — over 40%. At those
fractions no index can help, so its search workloads could not tell a working index from a missing
one. The prefix-search change above measured as no change at all there and was nearly discarded on
the strength of it. Widening the corpus to 676 distinct names made the same change 3.4x to 4.5x end
to end, against the 39.8x measured here in isolation.

Every index benchmark here therefore calls `assertSelectivity` in its setup: a trial whose
predicate stops matching the slice it was designed for fails loudly instead of quietly measuring
row fetching. When adding one, check what fraction of the table it matches. Anything above a few
percent is not measuring indexing.

### Reading these plans

A query plan tells you why something is slow. It does not establish that a better-looking plan is
faster; that depends on size and selectivity, and here it twice was not.

Benchmarks on a developer machine need care to mean anything. During this work several single-run
comparisons produced double-digit effects that vanished under repetition, including one on a
read-only workload that no index change could touch. What worked:

- Interleave the arms. Run A, B, A, B, not all of A then all of B; machine state drifts.
- At least three repetitions per arm, and compare the spread, not just the medians. Overlapping
  ranges are not a result.
- Keep a control group — measurements the change cannot affect. Their spread is the noise floor.
- Ignore relative deltas on anything below a few milliseconds.
- Run nothing else on the machine while a sweep is going. A Gradle build counts, and so does a
  rebase.

The JMH benchmarks need none of this themselves: they fork, warm and report a confidence interval.
The discipline is for end-to-end comparisons run by hand.

## A failing benchmark does not fail the build

kotlinx-benchmark 0.5.0 builds its JMH `Runner` with `shouldFailOnError` left at JMH's default of
`false`, and exposes no setting to change it. A benchmark whose `@Setup` throws is reported as
`<failure>` in the console, omitted from the JSON report, and the process still exits 0. The report
has no failure or error field, so a run that lost three of twenty benchmarks is indistinguishable
from one configured to run seventeen.

This is not hypothetical. `StringIndexCollationBenchmark` asserts that its two arms produce
different query plans; an earlier version built both arms over the same collation, and three of its
six combinations aborted in setup while Gradle reported `BUILD SUCCESSFUL`.

`engine/build.gradle.kts` therefore watches the runner's own output and fails the task when a
failure marker appears. That check is what the CI job stands on.

## What CI measures, and how it compares

Two tiers, chosen by what triggered the run.

**On a pull request, `:engine:prBenchmark` and `:engine:prNoisyBenchmark`, twice.** The job checks
out the base branch beside the head and runs the same tier against each, on the same runner,
minutes apart. The comment on the pull request is the difference between the two.

The tier is split in two so each benchmark gets the sampling it needs. `prBenchmark` runs
everything except `SqliteTuningBenchmark` — journal and fsync settings mean nothing on tmpfs, and
its question is settled — at ten half-second iterations, with the scaling sweeps pinned to their
smallest size (`rows=1000`, `changeCount=50`). `prNoisyBenchmark` runs the CRUD and `MoreResources`
benchmarks at ten one-second iterations instead: an indexed update takes about 300 ms on a runner,
so a half-second iteration would hold a single disk write.

That pairing is the point. Two scores from two CI jobs cannot be compared — shared runners differ
in machine class between jobs, and this suite has produced double-digit phantom effects from
exactly that. Two scores from one machine a few minutes apart can be, and their errors say by how
much. A row is flagged only when the difference lies outside its own 99.9% confidence interval and
is at least 5%: a tight-but-tiny shift and a large-but-noisy one are each left alone. The interval
of a difference combines the two errors in quadrature; the common shortcut of requiring the two
intervals not to overlap adds them instead, which is conservative enough to leave six more
benchmarks unable to see a 5% change. A flag is a prompt to look, not a verdict — each side is
still a single run.

An unflagged row is not necessarily unchanged. When the two errors together are wider than 5% of
the score, a 5% regression could not have been told apart from noise, and the row says **too noisy**
with the smallest change it could have caught. Read a blank as "no change of 5% or more", and a
too-noisy row as "no information".

Iteration count matters more than it looks. JMH's interval scales with Student's t, which at five
iterations is 8.47 and at ten is 4.78, so doubling the iterations shrinks every interval to about
40% of its width rather than by the square root alone. On the first CI run, at five iterations, 29
of the 54 pull-request benchmarks could not show a 5% change. Projected from those same runs, ten
iterations leaves about 13 — mostly the disk-bound CRUD and SQLite-tuning rows, which is the honest
limit of a shared runner.

On a pull request the benchmark databases live on tmpfs (`-Pbenchmark.tmpdir=/dev/shm/benchmarks`):
the runner's disk varied up to 3x within one job, which would drown out any code change. Write
benchmarks still run their SQL, indexing and cascades; only disk speed is removed.

If the base branch predates the benchmarks, its run fails, that failure is tolerated, and the
comment shows the head on its own with the "indicative only" caveat.

**On a push to `main`, `:engine:benchmark`, once.** The full tier, scaling sweeps included. Its
artifact is the record a later trend would be built from; nothing consumes it yet.

Locally, the same tasks: `prBenchmark` and `prNoisyBenchmark` for a quick check, `indexBenchmark`
for the sweeps, `benchmark` for everything. Run nothing else while they run.

CI therefore establishes that the benchmarks run and their assertions hold, and on a pull request
whether the head is measurably slower than its base on that runner. It establishes nothing about a
device: this is desktop JVM, a good indicator for algorithmic cost and a poor one for anything
I/O-bound.
