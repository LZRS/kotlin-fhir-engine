# Benchmark results

How the engine performs, measured by the micro benchmarks in `:engine` on CI. How to run them is in
[benchmarking.md](benchmarking.md).

_Numbers pending the CI run of `:engine:benchmark` at full size._

## Machine

| | |
|---|---|
| Runner | _pending_ |
| CPU | _pending_ |
| Memory | _pending_ |
| OS | _pending_ |
| JVM | _pending_ |
| Run | _pending_ |

A shared GitHub runner, not a phone. These numbers show algorithmic cost and how it scales; a device
will be slower, most of all on disk. Compare numbers only within one run.

## At a glance

_Pending: read by id, each search kind, a write per resource, a sync page, an upload batch._

## Search

### Index use per search shape

From `SearchQueryPlanTest`, on the schema this run measured.

| Search                  | Index columns narrowed             | Covering |
|-------------------------|------------------------------------|----------|
| token, code only        | all three                          | yes      |
| token with a system     | all three                          | no       |
| reference, uri          | all three                          | no       |
| number                  | all three, range on the value      | no       |
| quantity, no unit       | all three, range on the value      | no       |
| **quantity with a unit**| **two and the range — `index_code` unused** | no |
| string `:exact`         | all three                          | no       |
| string prefix, contains | two — `index_value` unused         | no       |
| date, dateTime          | two — the range columns unused     | yes      |
| `_revinclude`           | all three, then the resource by uuid | no     |
| **`_include`**          | **two — the join compares a concatenation, so neither side seeks** | no |

Sorting is never index-backed: every sorted search builds two temporary B-trees.

### Result size

_Pending: `SearchResultSizeBenchmark`._

`Search.execute` rescans the whole resolved list once per base resource, so `_include` and
`_revinclude` grow with the product of the page and the included resources. Grouping the resolved
resources once, by key, makes it linear and needs no schema change.

### Sorting

_Pending: `SortBenchmark`._

`count`/`from` does not bound the work on a sorted search. Rows do not arrive in order, so `LIMIT`
cannot stop early, and a sorted first page orders the whole corpus. Filtering first removes most of
it. Avoid a sorted search with no filter or a weak one, such as all patients in alphabetical order.

## Known shortfalls

Each is pinned by a test in `SearchQueryPlanTest`.

**Prefix string search** compiles to `index_value LIKE ? || '%' COLLATE NOCASE` and narrows on
`(resourceType, index_name)` only. SQLite applies its LIKE optimisation only to a literal or plain
parameter pattern, and uses an index only when its collation matches the comparison's; both must be
fixed. Fixing them costs `:exact` its index unless a second column mirrors `index_value`, and needs
a schema version bump. _Pending: `StringIndexCollationBenchmark`._

**`_include`** joins on `re.resourceType||'/'||re.resourceId = rie.index_value`. SQLite cannot seek
on an expression, so neither side uses its index and the work is the product of the two tables.
`_revinclude` binds `type/id` strings built in Kotlin and seeks; doing the same for `_include` needs
no schema change. _Pending: `SearchExecutionBenchmark`._

**Quantity with a unit**: the index is `(resourceType, index_name, index_value, index_code)`. The
range on `index_value` comes before `index_code`, so the unit is not used. Putting the unit first
helps a search with a unit and hurts one without; keeping both indices takes the gain without the
loss. _Pending: `QuantityIndexShapeBenchmark`._

**Reference and uri lookups are not covering.** Every filter subquery selects only `resourceUuid`;
the token index ends in that column and the reference and uri indices do not. Reference lookups
also carry chained, `_has` and `_revinclude` searches. _Pending: `LookupIndexCoveringBenchmark`._

**Token search with a system** adds `IFNULL(index_system,'') = ?`, and the token index does not
carry `index_system`, so each match costs a row fetch. _Pending: `TokenIndexShapeBenchmark`._

**Date and dateTime** indices are `(resourceType, index_name, resourceUuid, index_from, index_to)`.
`resourceUuid` sits between the equality prefix and the range columns, so no range can use the
index. _Pending: `DateIndexShapeBenchmark`._

## Writes and sync

_Pending: `EngineCreateBenchmark`, `EngineUpdateBenchmark`, `EngineDeleteBenchmark`,
`BulkImportBenchmark`, `SyncDownloadBenchmark`, `UploadAssemblyBenchmark`, `DatabaseOpenBenchmark`._

`syncDownload` reads and deserializes the whole pending queue once per page, only to intersect ids
with the page, so a download slows as the queue grows. The intersection also matches on resource id
only: a downloaded Patient that shares an id with a pending Observation change counts as a conflict,
and resolving it throws `ResourceNotFoundException`.

### Reads during a sync

_Pending: `ConcurrentAccessBenchmark`._

Under the rollback journal a writer holds an exclusive lock for its transaction and readers wait.
Under WAL they do not. The engine opens in WAL, and `JournalModeTest` pins it.

## Settled questions

Answered by benchmarks that have since been removed; the code is in git history.

- **SQLite tuning.** `ANALYZE` and `synchronous` made no difference outside the noise.
- **Batch size.** Bigger transactions are faster, and the gain flattens after about a thousand
  resources. `DatabaseImpl` writes a batch in one transaction.
- **Protobuf payloads.** Half the disk and somewhat faster reads, against a destructive schema
  migration and a wire format that is not self-describing. Not adopted.
- **Network path.** Requesting and parsing a sync page costs well under a millisecond on the client;
  the time is in the network and the ingest.
