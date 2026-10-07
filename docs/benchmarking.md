# Benchmarking

The micro benchmarks in `:engine` measure pure-CPU functions and SQLite.

```bash
./gradlew :engine:prBenchmark :engine:prNoisyBenchmark   # what CI runs on a pull request
./gradlew :engine:indexBenchmark   # the sweeps; tens of minutes
./gradlew :engine:benchmark        # everything at full size; what CI runs on a push to main
```

## Micro benchmarks

Sources are in `engine/src/desktopBenchmark/kotlin/` and can use the engine's `internal`
declarations. They run on the desktop JVM only, under JMH.

| Class                            | What it measures                                                                        |
|----------------------------------|-----------------------------------------------------------------------------------------|
| `ResourceIndexerBenchmark`       | `ResourceIndexer.index()` — a FHIRPath evaluation per search parameter, on every write  |
| `ResourceSerializerBenchmark`    | The serialize/deserialize floor under every read and write                              |
| `JsonDiffBenchmark`              | `JsonDiff.diff()`, run on every update                                                  |
| `UcumCanonicalBenchmark`         | Canonicalizing a quantity, which every indexed value and every filter pays              |
| `SearchQueryBenchmark`           | `Search.getQuery()` and `XFhirQueryTranslator` — per-query cost, independent of how much is stored |
| `SearchExecutionBenchmark`       | A search end to end: the query, the rows, a parse per row, and `_include`/`_revinclude` |
| `SearchResultSizeBenchmark`      | The same, swept by how many resources come back                                         |
| `SortBenchmark`                  | What sorting costs, and whether paging escapes it                                       |
| `DateIndexShapeBenchmark`        | Date range search under each date index column order, from 1,000 to 50,000 rows         |
| `StringIndexCollationBenchmark`  | Prefix search under each string index collation, from 1,000 to 50,000 rows              |
| `QuantityIndexShapeBenchmark`    | Quantity search, with and without a unit, under each quantity index column order        |
| `LookupIndexCoveringBenchmark`   | Reference and uri lookups with and without `resourceUuid` in the index                  |
| `TokenIndexShapeBenchmark`       | Token search with and without a system, with and without `index_system` in the index    |
| `ConcurrentAccessBenchmark`      | A read competing with a writer, as during a long sync, under each journal mode          |
| `EngineCreateBenchmark`, `EngineUpdateBenchmark`, `EngineDeleteBenchmark` | Writes through `DatabaseImpl`: one transaction, and a local change per resource |
| `BulkImportBenchmark`            | A download page written in one transaction, with no local change recorded               |
| `ResourceReadBenchmark`          | Reading by id through `ResourceDao`                                                     |
| `SyncDownloadBenchmark`          | Ingesting a download through `syncDownload`, swept by the size of the pending queue      |
| `LocalChangeReadBenchmark`       | Reading the pending queue and its references, which is where an upload starts           |
| `UploadAssemblyBenchmark`        | Squashing pending changes into patches, and patches into upload requests                |
| `PatchOrderingBenchmark`         | Tarjan's over the pending-upload graph, the only cost that grows with queue length      |
| `DatabaseOpenBenchmark`          | Opening the database, fresh and seeded                                                  |

`indexBenchmark` runs the classes that carry a `@Param` grid or a seeded corpus, at full size. The
pull-request tier runs most of them at their smallest size.

The index-shape, sort, search and concurrency classes seed rows straight into the index tables, not
through `FhirEngine`, which would add about 200 us of FHIRPath per resource to setup. Each asserts
that its query selects the slice it was designed for (see [Selectivity](#selectivity)). The
collation sweep also asserts that its two arms produce different query plans, because an index
inherits its column's collation.

### Current results

`StringIndexCollationBenchmark`, us/op at 1k/10k/50k rows: `binary` 65.7, 534.2, 3139.9; `nocase`
22.8, 31.9, 78.9. `binary` scans and `nocase` seeks: 39.8x at 50,000 rows. The engine ships
`binary`; see [Known shortfalls](#known-shortfalls).

`DateIndexShapeBenchmark`: the reordered index is 11-13% faster at every size, against a selective
window. Not enough to pay for the extra index and its write cost; see
[Known shortfalls](#known-shortfalls).

`QuantityIndexShapeBenchmark`, us/op at 1k/10k/50k rows. With a unit: `current` 100.8, 420.0,
1917.9; `codeFirst` 104.1, 262.3, 1256.8, 34% faster at 50,000 rows. Without a unit: `current`
155.5, 1053.2, 4701.3; `codeFirst` 340.0, 2644.2, 12188.8, 2.6x slower. `both` keeps the gain
without the loss: 91.0, 297.5, 1244.8 with a unit; 135.3, 1097.1, 4883.4 without. See
[Known shortfalls](#known-shortfalls).

`LookupIndexCoveringBenchmark`: appending `resourceUuid` saves about 25% on a reference lookup and
21% on a uri lookup at 50,000 rows (5320 to 3948 us/op, and 5359 to 4215). At 1,000 rows the arms
are equal, because the whole index is cached. The gap at 50,000 rows is just outside the combined
error, so its size is provisional.

`TokenIndexShapeBenchmark`: not yet measured.

`SortBenchmark`: an unsorted first page is flat in corpus size (87.7, 107.4, 79.3 us/op at
1k/10k/50k), because `LIMIT` stops at fifty rows. A sorted first page is linear (1320, 10593, 48886
us/op), because the whole corpus is ordered first. At 50,000 rows a sorted page costs **617x** an
unsorted one. Sorting an already-filtered slice costs about 5% (6829 against 7163 us/op at 50,000
rows). See [Sorting](#sorting).

`SearchExecutionBenchmark`, us/op over 1,000 patients and 1,000 observations: string `:exact` 27.1,
reference 36.5, token 62.6, number range 64.7, count 73.4, string prefix 81.6, `:contains` 87.7,
unfiltered page of fifty 148.0, `_revinclude` 122.5, `_include` 2229.1.

`_include` costs 36x the same filter without it; `_revinclude` costs 1.5x. The `_include` join
compares `re.resourceType||'/'||re.resourceId = rie.index_value`, and SQLite cannot seek on an
expression, so neither side uses its index. `_revinclude` binds `type/id` strings built in Kotlin,
so both sides seek. `SearchQueryPlanTest` pins both plans.

`SearchResultSizeBenchmark`, us/op over a fixed 10,000-patient corpus, varying only the page size:

| page | `page` | `pageWithInclude` | `pageWithRevInclude` |
|-----:|-------:|------------------:|---------------------:|
| 1 | 29.8 | 67.4 | 54.7 |
| 100 | 323.8 | 5,914.4 | 2,183.1 |
| 1,000 | 3,018.3 | 21,490.9 | 153,720.5 |
| 10,000 | 33,823.6 | — | 14,320,242.4 |

A plain page costs about 3.0 us per resource returned, over 27 us of query. On a Tab A9 over 20,000
patients the same work cost 0.40 ms per resource.

The include columns are not linear. A `_revinclude` over a page of 10,000 takes **14.3 s**, against
34 ms for the page alone. `Search.execute` rescans the whole resolved list once per base resource,
and the revInclude side also rebuilds the `type/id` key inside that scan. The include column is
quadratic too, but its join still dominates at these sizes; `—` is not measured. Grouping the
resolved resources once, by key, takes the 10,000-row page from 14.3 s to 87 ms (**164x**) and needs
no schema change.

`EngineCreateBenchmark`: fifty patients cost 13.5 ms, 270 us each. `BulkImportBenchmark` writes 500
in one transaction at 305 us each, without a local-change ledger.

Each seeded patient carries two references, so an update diffs them in `LocalChangeDao`. This adds
about 8% to `EngineUpdateBenchmark` (14.2 to 15.4 ms for fifty). Re-indexing dominates.

`SyncDownloadBenchmark`, ingesting 1,000 resources in ten pages over a constant corpus: 1,051 ms
with no pending changes, 1,037 ms with 100, 1,137 ms with 1,000. `syncDownload` calls
`getAllLocalChanges` once per page and deserializes every entry to intersect ids with the page. A
query for only the page's edited resources, with the resource type leading so it uses the
`(resourceType, resourceId)` index, flattens it: 1,082, 1,056, 1,032 ms, about 9% at 1,000.

Hold the corpus constant when sweeping the queue; varying it inflates the effect to 22%.

The intersection matches on resource id only. A downloaded Patient that shares an id with a pending
Observation change counts as a conflict, and resolving it throws `ResourceNotFoundException`.

`ConcurrentAccessBenchmark`, a prefix search over 20,000 patients with and without a thread
committing small transactions alongside it:

| journal | alone | with a writer |
|---------|------:|--------------:|
| `wal` | 1,070 us | 1,538 us |
| `delete` | 1,104 us | 23,637 us |

Under the rollback journal a writer holds an exclusive lock for its transaction and readers wait.
The `delete` figure varies widely (±59,893) because lock waits arrive in bursts. Under WAL the read
pays about 44%. The engine already opens in WAL; this is the reason to stay on it.

`UploadAssemblyBenchmark`, fifty resources each with an insert and two updates: squashing into one
patch per resource takes 253.2 us against 2.9 us for keeping every change, because it replays each
RFC 6902 payload over the one before. Building requests costs 73.4 us as bundles and 71.9 us as
individual URLs.

`DatabaseOpenBenchmark`: 1.79 ms to create a schema, 0.49 ms to reopen one holding 500 patients.

### Settled questions

Measured by benchmarks that have since been removed; the code is in git history.

- **SQLite tuning.** `ANALYZE` and `synchronous` changed nothing outside the noise, on macOS.
- **Batch size.** 5,000 patients took 3,827 ms one at a time and 1,590 ms in one transaction; the
  gain flattens after a thousand. Through `DatabaseImpl`, fifty patients cost 13.5 ms; through
  `ResourceDao` a resource at a time, 104.1 ms.
- **Protobuf payloads.** Half the disk and about 14% faster reads, against a destructive schema
  migration and a wire format that is not self-describing. Not adopted.
- **Network path.** With a mocked server, a page of 100 resources costs under a millisecond to
  request and parse. A page on the device took about 1.6 s, so the time is in the network and the
  ingest.

## Index usage

`SearchQueryPlanTest` (in `:engine`'s `desktopTest`) runs `EXPLAIN QUERY PLAN` over each search
shape and asserts which SQLite index it uses.

```bash
./gradlew :engine:desktopTest --tests "*SearchQueryPlanTest*"
```

A plan shows a lost index in about a second, on an empty database. A timing run shows it only at a
large corpus, and a warm page cache can hide it.

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
`resourceUuid` and theirs do not. `_include` is the only plan whose cost grows with the product of
two tables. Sorting is never index-backed: every sorted search builds two temporary B-trees.

`StringSearchMatchingTest` runs real searches against a real database and asserts which rows come
back. `SearchTest` only compares generated SQL, so it cannot catch a collation change that returns
the wrong rows.

### Known shortfalls

Each is pinned by a test that names it.

**Prefix string search** compiles to `index_value LIKE ? || '%' COLLATE NOCASE` and narrows on
`(resourceType, index_name)` only. Two conditions block the index, and both must be fixed: SQLite
applies its LIKE optimisation only to a literal or plain parameter pattern, and uses an index only
when its collation matches the comparison's. Fixing both is worth 39.8x at 50,000 rows. It
costs `:exact` its index unless a second column mirrors `index_value`, and the collation change
needs a schema version bump.

**Date and dateTime** indices are `(resourceType, index_name, resourceUuid, index_from, index_to)`.
`resourceUuid` sits between the equality prefix and the range columns, so no range can use the
index. Moving it last and adding a second index that leads with `index_to` measured about 13% worse
end to end (a birth-date range search and a delete, interleaved, n=3), and 11-13% better in a
sweep from 1,000 to 50,000 rows against a selective window. Unchanged until a workload needs it.

**Quantity with a unit**: the index is `(resourceType, index_name, index_value, index_code)`, and a
search with a unit emits `index_system = ? AND index_code = ? AND index_value >= ? AND index_value
< ?`. The range on `index_value` comes before `index_code`, so the unit is not used. Swapping the
two columns gains 34% at 50,000 rows and costs 2.6x on a search without a unit. Keeping both indices
takes the gain without the loss, at no measurable write cost: 500 observations with a quantity
imported in 208.1 ms without the second index and 203.8 ms with it.

**`_include` cannot use an index.** Its join compares a concatenation, so neither side seeks, and
the work is the product of the two tables. `SearchExecutionBenchmark` measures 2,229 us with
`_include` against 62.6 us without, at 1,000 patients and 1,000 observations. The fix is to build
the `type/id` strings in Kotlin and bind them, as `_revinclude` does. It needs no schema change.

**Reference and uri lookups are not covering.** Every filter subquery selects only `resourceUuid`.
The token index ends in that column and its plan is COVERING; the reference and uri indices stop at
`index_value`. Appending the column gains about 20-25% at 50,000 rows and nothing at 1,000.
Reference lookups also carry chained, `_has` and `_revinclude` searches. The write cost is nothing
measurable: 500 patients with two references each imported in 153.6 ms before and 154.1 ms after.

### Sorting

`Search.sort` compiles to a LEFT JOIN onto the index table, a GROUP BY, and an ORDER BY. The join
uses `(resourceUuid, index_name, index_value)` as a covering index; the GROUP BY and the ORDER BY
each build a temporary B-tree.

**`count`/`from` does not bound the work on a sorted search.** Rows do not arrive in order, so
`LIMIT` cannot stop early, and a sorted first page orders the whole corpus: 617x an unsorted page
at 50,000 rows. Every page pays this.

Filtering first removes most of it: sorting a 1/676 slice costs about 5%. Avoid a sorted search
with no filter or a weak one, such as all patients in alphabetical order.

Open: an index on `ResourceEntity(resourceType, resourceUuid)` might remove the GROUP BY B-tree.
For a string sort, the `HAVING MIN(...) >= <sentinel>` the sort emits compares text against an
integer, which SQLite always resolves the same way.

### Selectivity

An index pays only when the predicate rejects most rows, so selectivity depends on the dataset as
well as the query. A corpus of eight family names, where `family = "Smith"` matches one patient in
eight, hid the prefix-search change entirely; with 676 names it measured 3.4x to 4.5x end to end.

Every index benchmark calls `assertSelectivity` in its setup and fails if its predicate stops
matching the slice it was designed for. Keep a new predicate below a few percent of the table.

### Comparing runs by hand

A better plan is not always faster; that depends on size and selectivity. JMH forks, warms and
reports a confidence interval itself. For end-to-end comparisons run by hand:

- Interleave the arms: A, B, A, B.
- Run at least three repetitions per arm, and compare the spreads, not only the medians.
- Keep a control measurement the change cannot affect. Its spread is the noise floor.
- Ignore relative deltas below a few milliseconds.
- Run nothing else on the machine, including a Gradle build.

## A failing benchmark does not fail the build

kotlinx-benchmark 0.5.0 runs JMH with `shouldFailOnError` set to `false`, and has no setting to
change it. A benchmark whose `@Setup` throws prints `<failure>`, is left out of the JSON report, and
the process exits 0. `engine/build.gradle.kts` scans the runner's output and fails the task when a
failure marker appears.

## What CI measures

**On a pull request**, the job runs `:engine:prBenchmark` and `:engine:prNoisyBenchmark` against
the base branch and then the head, on the same runner, and comments with the difference. Scores
from two different jobs are not comparable, because shared runners differ in machine class.

- `prBenchmark` runs everything except `SyncDownloadBenchmark` and the `prNoisyBenchmark` classes,
  at ten half-second iterations, with the sweeps at their smallest size (`rows=1000`,
  `changeCount=50`, `results=100`).
- `prNoisyBenchmark` runs the engine write, `BulkImport`, `ResourceRead` and `DatabaseOpen` classes
  at ten one-second iterations, because one invocation can take about 300 ms.

A row is flagged when the difference is outside its 99.9% confidence interval and is at least 5%.
The interval of a difference combines the two errors in quadrature. A row whose combined error is
wider than 5% says **too noisy**, with the smallest change it could detect. A blank row means no
change of 5% or more.

Ten iterations instead of five drop Student's t from 8.47 to 4.78, which narrows every interval to
about 40% of its width.

On a pull request the databases live on tmpfs (`-Pbenchmark.tmpdir=/dev/shm/benchmarks`), because
the runner's disk speed varied up to 3x within one job. SQL, indexing and cascades still run.

If the base branch has no benchmarks, its run fails, and the comment shows the head alone.

A pull request labelled `benchmark:full` runs `:engine:benchmark` on both sides instead. To apply
it to an open pull request, add the label and re-run the job.

**On a push to `main`**, `:engine:benchmark` runs once, at full size.

Both tiers also write their table to the run's summary page.

CI runs on the desktop JVM: a good indicator for algorithmic cost, and a poor one for I/O-bound work
on a device.
