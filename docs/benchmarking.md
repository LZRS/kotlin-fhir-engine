# Benchmarking

Measures the engine's CRUD, Search DSL and sync paths across the platforms it ships on. Run manually;
nothing here runs in CI.

## Modules

| Module | What it is |
|---|---|
| `:benchmarks:core` | The workload catalogue and the in-process runner. Every platform runs these same workloads. |
| `:benchmarks:app` | A driver app launched by intent, so Android can be measured as a real app. |
| `:benchmarks:macro` | Macrobenchmark that drives the app and reads trace sections back. |

Workloads are defined once, in `:benchmarks:core`. Neither of the other modules defines its own.

## Quick start

```bash
# Desktop — fastest, no device needed
./gradlew :benchmarks:core:desktopTest -Pbenchmark.profile=standard
```

The report prints as a table and is written to
`benchmarks/core/build/reports/benchmarks/desktop-<timestamp>.json`.

## Desktop

```bash
./gradlew :benchmarks:core:desktopTest                              # standard profile
./gradlew :benchmarks:core:desktopTest -Pbenchmark.profile=smoke    # quick check
./gradlew :benchmarks:core:desktopTest -Pbenchmark.groups=search    # one group
```

| Flag | Default | Meaning |
|---|---|---|
| `-Pbenchmark.profile` | `standard` | `smoke` (10 patients), `standard` (100), `large` (1000) |
| `-Pbenchmark.groups` | all | Comma-separated: `crud`, `search`, `sync` |
| `-Pbenchmark.warmup` | `2` | Discarded iterations |
| `-Pbenchmark.iterations` | `5` | Measured iterations |
| `-Pbenchmark.seed` | fixed | Dataset seed. Changing it changes the fingerprint. |
| `-Pbenchmark.report.dir` | `build/reports/benchmarks` | Where the JSON lands |

## Android

Use a **physical device**. Emulator numbers are host-bound and meaningless; an emulator is only good
for checking that the plumbing works.

### The driver app on its own

```bash
./gradlew :benchmarks:app:installRelease

# One workload
adb shell am start -n dev.ohs.fhir.engine.benchmark.app/.BenchmarkActivity \
  -a dev.ohs.fhir.engine.benchmark.RUN -e workload search.observation_by_code -e profile smoke

# A whole group, which also writes a JSON report
adb shell am start -n dev.ohs.fhir.engine.benchmark.app/.BenchmarkActivity \
  -a dev.ohs.fhir.engine.benchmark.RUN -e groups search -e profile standard

./gradlew :benchmarks:app:pullBenchmarkReports
adb logcat -d -s BenchmarkDriver
```

The status view reports `starting` → `ready` → `done`, or `failed <exception>`. `ready` marks the end
of untimed setup.

### Macrobenchmark

```bash
./gradlew :benchmarks:macro:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.profile=standard
```

One class at a time:

```bash
./gradlew :benchmarks:macro:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ohs.fhir.engine.benchmark.macro.FhirEngineSearchMacrobenchmark
```

Results land in
`benchmarks/macro/build/outputs/connected_android_test_additional_output/`, alongside the Perfetto
traces.

**On an emulator**, macrobenchmark refuses to run without:

```bash
-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,UNLOCKED,DEBUGGABLE,LOW-BATTERY
```

Suppressing those does not make the numbers meaningful. It only lets the run proceed.

## Web

Needs a Chromium-based browser. Chromium satisfies Karma's `CHROME_BIN` directly:

```bash
export CHROME_BIN=/Applications/Chromium.app/Contents/MacOS/Chromium
./gradlew :benchmarks:core:wasmJsBrowserTest
./gradlew :benchmarks:core:jsBrowserTest
```

The report is printed to the console rather than written to disk.

## iOS

```bash
./gradlew :benchmarks:core:iosSimulatorArm64Test
```

Indicative only: the report is printed, not written, and only the synthetic dataset is available.

## Reading the results

Every report records the platform and a dataset `fingerprint`. **Two reports are comparable only if
both match.** Different platform, different seed, different population, or a different dataset kind
all mean the numbers are measuring different things.

Per workload the report carries the raw `samplesMillis` plus min/median/p90/max/mean/stdDev and
`medianMillisPerOp`. Prefer the median; `p90` shows how noisy the run was.

### Sanity checks

Before trusting a run:

- **The search medians must show a spread.** If they are all alike and fast, the page cache was never
  disturbed and the search numbers mean nothing. On desktop at `standard` the spread is roughly
  0.6 ms to 108 ms.
- **Every macrobenchmark `TraceSectionMetric` must be non-zero.** A zero means the section never
  reached the trace — usually a debuggable build, a missing `<profileable>`, or a workload id that
  does not match the span name. This fails silently.
- **`isolationNote` must be null.** When set, the platform could not honour the isolation the
  workload asked for and the number is weaker than it looks.
- **Run the same profile twice.** Median-over-median drift should be well under whatever threshold
  you want to gate on. Macrobenchmark's own variance is roughly 5–10%.

## Known limitations

- **Macrobenchmark currently measures nothing.** `connectedReleaseAndroidTest` builds, installs and
  runs, and the driver app completes its workloads, but every `TraceSectionMetric` comes back
  `median=0.0` with `Count=0.0` — the trace sections never reach the Perfetto trace. The test
  **passes** in this state, so treat a green macrobenchmark run as meaningless until a non-zero
  count appears. Ruled out so far: `<profileable android:shell="true"/>` is present in the release
  APK, and switching the span from `android.os.Trace` to `androidx.tracing.Trace` changed nothing.
  Next things to try: `Trace.forceEnableAppTracing()`, whether the emulator's atrace is the problem
  at all (retry on a physical device first), and whether the span landing on a background thread
  matters.

- **Web has no in-process reset.** Closing the database wedges the SQLite Web Worker; not closing
  leaves it holding the exclusive OPFS handle so a reopen never completes. `FRESH_DATABASE`
  therefore degrades to `CLEAR_TABLES` on web, which the report records. A genuinely cold web
  measurement needs a page reload.
- **Upload sync is not measured.** `syncUpload` expects response mapping types that are `internal`,
  so an external caller can only report failure. Measuring upload needs a real FHIR server.
- **Synthea data is not wired up yet.** All numbers currently come from the deterministic synthetic
  dataset. It is fine for comparing runs against each other, not for comparing against android-fhir.
- **iOS writes no report file.**
- **`js`/`wasmJs` have two pre-existing `FhirEngineImplTest` failures** unrelated to benchmarking.

## Micro benchmarks

The micro benchmarks in `:engine` measure pure-CPU functions and SQLite. What they found is in
[benchmark-results.md](benchmark-results.md).

### Running

```bash
./gradlew :engine:prBenchmark :engine:prNoisyBenchmark   # what CI runs on a pull request; smallest sizes
./gradlew :engine:indexBenchmark   # the sweeps at full size
./gradlew :engine:benchmark        # everything at full size; CI on main or a `benchmark:full` PR
```

Run nothing else on the machine while they run.

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
| `DateIndexShapeBenchmark`        | Narrow and wide date range searches under each date index column order                  |
| `StringIndexCollationBenchmark`  | Prefix search under each string index collation, from 1,000 to 50,000 rows              |
| `QuantityIndexShapeBenchmark`    | Quantity search, with and without a unit, under each quantity index column order        |
| `LookupIndexCoveringBenchmark`   | Reference and uri lookups with and without `resourceUuid` in the index                  |
| `TokenIndexShapeBenchmark`       | Token search with and without a system, with and without `index_system` in the index    |
| `ConcurrentAccessBenchmark`      | A read competing with a writer, as during a long sync, under each journal mode          |
| `EngineCreateBenchmark`, `EngineUpdateBenchmark`, `EngineDeleteBenchmark` | Writes through `DatabaseImpl`: one transaction, and a local change per resource |
| `BulkImportBenchmark`            | A download page written in one transaction, with no local change recorded               |
| `ResourceReadBenchmark`          | Reading by id through `ResourceDao`                                                     |
| `SyncDownloadBenchmark`          | Ingesting a download through `syncDownload`, as inserts and as updates, by queue size    |
| `LocalChangeReadBenchmark`       | Reading the pending queue and its references, which is where an upload starts           |
| `UploadAssemblyBenchmark`        | Squashing pending changes into patches, and patches into upload requests                |
| `PatchOrderingBenchmark`         | Tarjan's over the pending-upload graph, the only cost that grows with queue length      |
| `DatabaseOpenBenchmark`          | Opening the database, fresh and seeded                                                  |

The tasks map to configurations in `engine/build.gradle.kts`:

- `main` (`benchmark`): every class, every `@Param` value, five warmups and ten one-second
  iterations.
- `pr` (`prBenchmark`): everything except `SyncDownloadBenchmark`, `ConcurrentAccessBenchmark` and
  the `prNoisy` classes, in three forks of three warmups and five half-second iterations, with the
  sweeps at their smallest size (`rows=1000`, `changeCount=50`, `results=100`) and the index
  benchmarks at the shipped index only (`shape=current`, `collation=binary`).
- `prNoisy` (`prNoisyBenchmark`): the engine write, `BulkImport`, `ResourceRead` and `DatabaseOpen`
  classes in three forks of five one-second iterations, because one invocation can take about
  300 ms.
- `index` (`indexBenchmark`): the classes with a `@Param` grid or a seeded corpus, at full size,
  three warmups and five half-second iterations.

### Writing a benchmark

- Seed index, sort, search and concurrency benchmarks straight into the index tables with
  `IndexBenchmarkDatabase`, not through `FhirEngine`. FHIRPath indexing would dominate the setup.
- Call `assertSelectivity` in `@Setup`. An index pays only when the predicate rejects most rows, so
  keep a predicate below a few percent of the table.
- When arms differ by index, assert in `@Setup` the plan each arm depends on (`planFor`). An index
  inherits its column's collation, so two arms can otherwise be the same index.
- Use a per-invocation `@Setup` only when one invocation takes milliseconds or more.

### Query plans

`SearchQueryPlanTest` (in `:engine`'s `desktopTest`) runs `EXPLAIN QUERY PLAN` over each search
shape and asserts which SQLite index it uses.

```bash
./gradlew :engine:desktopTest --tests "*SearchQueryPlanTest*"
```

A plan shows a lost index in about a second, on an empty database. A timing run shows it only at a
large corpus, and a warm page cache can hide it. Tests that pin a known shortfall are listed under
[Known shortfalls](benchmark-results.md#known-shortfalls).

`StringSearchMatchingTest` runs real searches against a real database and asserts which rows come
back. `SearchTest` only compares generated SQL, so it cannot catch a collation change that returns
the wrong rows.

### A failing benchmark does not fail the build

kotlinx-benchmark 0.5.0 runs JMH with `shouldFailOnError` set to `false`, and has no setting to
change it. A benchmark whose `@Setup` throws prints `<failure>`, is left out of the JSON report, and
the process exits 0. `engine/build.gradle.kts` scans the runner's output and fails the task when a
failure marker appears.

### Comparing runs by hand

A better plan is not always faster; that depends on size and selectivity. JMH forks, warms and
reports a confidence interval itself. For end-to-end comparisons run by hand:

- Interleave the arms: A, B, A, B.
- Run at least three repetitions per arm, and compare the spreads, not only the medians.
- Keep a control measurement the change cannot affect. Its spread is the noise floor.
- Ignore relative deltas below a few milliseconds.

### What CI measures

**On a pull request**, the job runs `:engine:prBenchmark` and `:engine:prNoisyBenchmark` against
the base branch and then the head, on the same runner, and comments with the difference. Scores
from two different jobs are not comparable, because shared runners differ in machine class.

The benchmarks are split across two jobs (`-Pbenchmark.shard=1` or `2`; `firstShard` in
`engine/build.gradle.kts`). Each job runs base and head for its share, so a row is compared on one
runner. The comment workflow merges the reports.

A row is flagged when the difference is outside its 95% interval and is at least 7.5%. The interval
is a pooled t-interval over the fork means: JIT decisions vary between forks more than one fork's
own interval shows. On identical code, one fork per side flagged 3–21 of 75 rows; three forks
flagged one at 5% and none at 7.5%. The comment shows the interval next to the change.

A row whose interval is wider than 7.5% is **too noisy**. Otherwise an unflagged row is
**unchanged**. The comment shows flagged rows and collapses the rest.

If either side has one fork, as when the base predates the forks setting, the comment falls back to
JMH's 99.9% intervals combined in quadrature.

The comment also counts the changed files the benchmarks compile: `commonMain`, `desktopMain`,
`desktopBenchmark` and the build files. With none changed, the benchmarks are skipped, and an
earlier comment says so. A `benchmark:full` pull request always runs.

On a pull request the databases live on tmpfs (`-Pbenchmark.tmpdir=/dev/shm/benchmarks`), because
the runner's disk speed varies within a job. SQL, indexing and cascades still run.

If the base branch has no benchmarks, its run fails, and the comment shows the head alone.

A pull request labelled `benchmark:full` runs `:engine:benchmark` on both sides instead. To apply
it to an open pull request, add the label and re-run the job.

**On a push to `main`**, `:engine:benchmark` runs once, at full size.

Every run writes its table to the run's summary page.

CI runs on the desktop JVM: a good indicator for algorithmic cost, and a poor one for I/O-bound work
on a device.
