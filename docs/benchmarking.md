# Benchmarking

Measures the engine's CRUD, Search DSL and sync paths across the platforms it ships on. Run
manually; nothing here runs in CI.

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
| `-Pbenchmark.groups` | `crud,search,sync` | Comma-separated. `server` is never a default. |
| `-Pbenchmark.dataset` | `synthetic` | `synthetic` or `synthea` |
| `-Pbenchmark.warmup` | `2` | Discarded iterations |
| `-Pbenchmark.iterations` | `5` | Measured iterations |
| `-Pbenchmark.seed` | `20260819` | Dataset seed. Changing it changes the fingerprint. |
| `-Pbenchmark.server` | none | Base URL for the `server` group, which is skipped without it |
| `-Pbenchmark.report.dir` | `build/reports/benchmarks` | Where the JSON lands |
| `-Pbenchmark.data.dir` | the packaged output | Where the Synthea corpus is read from |
| `-Pbenchmark.storage.dir` | `build/benchmark-db` | Where the engine's database file goes |

The first six also work on `jsBrowserTest`, `wasmJsBrowserTest` and `iosSimulatorArm64Test`;
`-Pbenchmark.server` works on Android and iOS but not on web. The three directory flags are desktop-only —
[Web](#web) and [iOS](#ios) say where those paths come from instead. Browser runs also have their
own lighter defaults.

## Synthea data

The synthetic dataset is the default and needs no tooling. For realistic numbers, generate Synthea
records instead:

```bash
./gradlew :benchmarks:core:packageBenchmarkData                       # download, generate, package
./gradlew :benchmarks:core:desktopTest -Pbenchmark.dataset=synthea    # both steps, if not yet built
```

Asking for `synthea` and not getting it is an error, not a downgrade: every platform fails the run
when the corpus is missing or holds no `Patient`, rather than measuring synthetic data in its place.

The first run downloads a ~200 MB jar into `~/.gradle/caches/synthea/<version>/`, outside the
project so it survives `clean`. The version and its SHA-256 are pinned in `gradle.properties`;
changing either changes the report fingerprint and makes earlier reports incomparable.

**Regenerating destroys what is already packaged.** `generateSyntheaData` deletes its output before
running, and the population comes from `benchmark.population`, which defaults to `10`. A command
that forgets the flag would therefore replace a 50,000-patient corpus that took half an hour to
build. `guardBenchmarkCorpus` fails the build instead:

```
A benchmark corpus of 50000 patients is already packaged, but this run asks for 10 and
generating would delete it. Pass -Pbenchmark.population=50000 to use what is there, or
-Pbenchmark.regenerate to replace it.
```

### What the corpus contains, and what is generated

Synthea runs with `-m pregnancy` and exports patients only. The full module set produces about 131
resources per patient — roughly 5 GB at 50,000 patients, and over half a day to insert on a
benchmark tablet, most of it observations no query would otherwise need in that volume.

So the corpus carries patients, encounters, organizations and practitioners; the observations and
conditions the search workloads query are **generated in code** against its real patient ids
(`AugmentedDataset`). That makes the per-patient clinical volume a number this repository sets
rather than one Synthea decides — currently 8 observations and 2 conditions, in `ClinicalMix`.

| `-Pbenchmark.population` | From the corpus | Generated | Total loaded |
|---|---|---|---|
| `10` (default) | ~75 | 100 | ~175 |
| `500` | ~2,350 | 5,000 | ~7,350 |
| `50000` | ~167,000 | 500,000 | ~667,000 |

The report records this: the dataset kind becomes `synthea+generated` and the fingerprint gains the
mix, so a run with a different mix over the same corpus is never mistaken for a comparable one.

Only the resource types the workloads touch are loaded. Synthea also emits `Claim`,
`ExplanationOfBenefit` and `DocumentReference`, which together dwarf everything else and which no
query looks at; loading them exhausts the heap for no benefit.

### Reading a corpus larger than memory

The corpus is streamed, never held. A 50,000-patient corpus parses to more than the 256 MB heap a
normal Android app gets, so `NdjsonDataset` keeps only counts, patient ids and a fingerprint, and
goes back to the files whenever the resources themselves are needed.

That scan still costs minutes at 50,000 patients, and every macrobenchmark iteration is a fresh
process. It is therefore cached beside the app, keyed by the corpus manifest, so only the first
process pays it.

Synthea's own output is not reproducible file-for-file — it stamps a run timestamp into some
filenames, e.g. `Organization.1787101630467.ndjson` — so packaging merges everything for a type
into `<Type>.ndjson` and writes a `manifest.json` beside it.

**A pinned seed does not give a reproducible corpus.** Two runs at the same `synthea.version`,
`benchmark.seed` and `benchmark.population` have produced different resource counts, and so
different fingerprints. Pinning narrows the variation; it does not remove it. The fingerprint
recorded in the report, not the seed, is what says whether two reports are comparable — regenerate
the dataset only when you are ready to rebaseline.

Parsing is lenient. Synthea emits US Core profiles and extensions the model does not carry, and a
strict parse would reject the corpus. Lines that still fail are counted and printed rather than
silently dropped.

## Android

Use a **physical device**. Emulator numbers are host-bound and meaningless; an emulator is only good
for checking that the plumbing works.

Keep the device awake. Android freezes cached background processes, so if the screen sleeps during a
long workload the run stalls at 0% CPU and the next launch cannot be confirmed. The driver app holds
the screen on and the harness wakes the device, but a device that sleeps for other reasons — low
battery, a policy — will still stall.

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

### Watching a run

A group run shows live progress on the device: which workload is in flight, warmup versus measured
iteration, elapsed time, and each finished workload's median as it lands. The header names the
phase, so the stretch after the last workload reads `reporting` while the JSON is being written
rather than looking like a stall. When the run ends the screen is the results table.

Completions are also written to logcat:

```bash
adb logcat -s BenchmarkDriver
```

A single-workload run (`-e workload <id>`) keeps the plain status text instead, because
macrobenchmark waits on that view and must not pay for a UI. That view reports
`starting` → `ready` → `done`, or `failed <exception>`. `ready` marks the end of untimed setup.

The screen costs the numbers a little. The elapsed clock ticks once a second, and each iteration
event redraws the current row; both can land while a workload is being measured, so the app's own
in-process numbers carry a small amount of UI work. Macrobenchmark drives the single-workload path,
never renders this screen, and is the authoritative Android measurement — treat the app's
group-mode report as indicative.

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

### Synthea on Android

```bash
./gradlew :benchmarks:macro:connectedReleaseAndroidTest -Pbenchmark.dataset=synthea
```

One flag does both jobs: it stages the packaged data into the driver app's assets and tells the run
to use it. Android cannot read the host filesystem, and `/data/local/tmp` is unreadable to an app
from API 30, so the corpus travels inside the APK — only the types a workload queries, about
5.7 MB at `benchmark.population=10`, which compresses to roughly 1 MB of APK.

**A Synthea run that cannot find the data fails.** Nothing substitutes the synthetic dataset for
it, so a build without the staged assets stops the run:

```
IllegalStateException: benchmark.dataset=synthea, but the Synthea data is not readable here: …
```

The driver puts that in its status view and in logcat, and the macrobenchmark's wait ends on it
rather than running to the timeout. Read the message with:

```bash
adb logcat -d -s BenchmarkDriver
```

On a run that does start, the driver logs what loaded, which is the only record of the dataset on
the single-workload path because it writes no report:

```bash
adb logcat -d -s BenchmarkDriver | grep dataset=
# dataset=synthea population=11 fingerprint=6ae5c742bfc713c6
```

**Check that every metric is non-zero before believing a run.** A macrobenchmark passes whether or
not it measured anything, so a green run proves nothing on its own:

```bash
python3 - <<'EOF'
import json, glob
f = glob.glob('benchmarks/macro/build/outputs/connected_android_test_additional_output/'
              'release/connected/*/*-benchmarkData.json')[0]
for b in json.load(open(f))['benchmarks']:
    for k, v in b['metrics'].items():
        if k.endswith('SumMs') and v['median'] == 0:
            print('ZERO:', k)
EOF
```

A zero means the trace section never formed a closed slice. `atrace` pairs begin and end **per
thread**, so anything that lets the measured block resume on a different thread than it started on
breaks the pairing silently — the name still appears in the trace, but nothing matches it. The
Android span therefore runs on one dedicated thread; see `BenchmarkSpan.android.kt`.

**On an emulator**, macrobenchmark refuses to run without:

```bash
-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,UNLOCKED,DEBUGGABLE,LOW-BATTERY
```

Suppressing those does not make the numbers meaningful. It only lets the run proceed.

### Sweeping every workload in isolation

At a large corpus the interesting question is which workloads survive, not only how fast they are.
One `connectedReleaseAndroidTest` cannot answer it: the six crud workloads share a single `@Test`,
so the first `OutOfMemoryError` ends the other five before they start.

`scripts/android_benchmark_sweep.py` runs one workload per Gradle invocation and aggregates the
results:

```bash
scripts/android_benchmark_sweep.py --population 50000
scripts/android_benchmark_sweep.py --population 50000 --triage-only
scripts/android_benchmark_sweep.py --only crud.create_batch --reuse-corpus
scripts/android_benchmark_sweep.py --groups server --server http://localhost:8080/fhir
```

Two phases. **Triage** runs every workload once, to find what survives. **Full** then reruns only
the survivors at measured iterations. At 50,000 patients that is the difference between learning
what breaks in an hour and learning it at the end of a day; `--triage-only` stops after the first
pass.

| Flag | Default | Meaning |
|---|---|---|
| `--population` | `50000` | Synthea patients |
| `--profile` | `standard` | Forwarded to the driver |
| `--timeout` | `30` | Wall-clock minutes per workload |
| `--triage-iterations` | `1` | Iterations in the triage pass |
| `--full-iterations` | `5` | Iterations in the full pass |
| `--only` | all | Workload ids to run instead of the whole catalogue |
| `--groups` | all | Restrict to `crud`, `search`, `sync` or `server` |
| `--server` | none | Base URL of a FHIR server; the `server` group needs it |
| `--reuse-corpus` | off | Use the packaged corpus on disk, skipping generation |
| `--dry-run` | off | Print the Gradle commands and stop |

Each workload gets two timeouts: an outer wall clock that kills the process group, and the driver's
own wait set below it, so the test reports which workload stalled rather than being killed
mid-sentence.

The sweep passes `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. Without it AGP
uninstalls the driver after every workload, taking the seeded database and the cached corpus scan
with it, and each of the 31 workloads starts from an empty database. Read-only workloads
(`Isolation.NONE`) keep whatever the previous one left, so the corpus is inserted once rather than
once per workload — measured at 580s down to 70s between iterations on a Galaxy Tab A9.

Every run is classified from its logcat, its Gradle log and its metrics:

| Status | Meaning |
|---|---|
| `ok` | Passed, and the trace section measured something |
| `no-metric` | Passed but measured zero — see the sanity check above |
| `oom` | `OutOfMemoryError`, or the low-memory killer took the driver |
| `timeout` | Ran past the clock with no out-of-memory evidence |
| `failed` | Anything else; the first exception line is kept |
| `skipped` | The `server` group when no `--server` was given |

Out of memory is checked before the timeout it causes. When the driver dies its status view never
settles and the wait runs to the clock, so reporting a timeout would name the symptom and hide the
cause.

Results land under `benchmarks/macro/build/sweeps/<timestamp>/`: `summary.json` and `summary.txt`
at the top, and per workload per phase the `benchmarkData.json`, the Gradle log and the logcat.
Each row records the driver's `dataset=… population=… fingerprint=…` line, so a report proves which
corpus it measured. Rows whose workload queries a resource type the corpus does not contain are
flagged — without that, an empty result set reads as a fast one.

The workload list is read from the catalogue sources rather than copied, and the script fails if a
group comes back empty, so a rename cannot silently shrink the sweep. Its own logic is tested:

```bash
python3 -m unittest discover -s scripts -t scripts
```

## Web

Needs a Chromium-based browser. Chromium satisfies Karma's `CHROME_BIN` directly:

```bash
export CHROME_BIN=/Applications/Chromium.app/Contents/MacOS/Chromium
./gradlew :benchmarks:core:wasmJsBrowserTest
./gradlew :benchmarks:core:jsBrowserTest
```

Both take the same `-Pbenchmark.*` flags as desktop, `-Pbenchmark.dataset=synthea` included:

```bash
./gradlew :benchmarks:core:jsBrowserTest -Pbenchmark.profile=smoke -Pbenchmark.dataset=synthea
```

With no flags a browser run uses the smoke profile, 1 warmup and 3 measured iterations, rather than
desktop's heavier defaults: a browser over OPFS is the slowest target by a wide margin.

The report lands in `benchmarks/core/build/reports/benchmarks/<js|wasmJs>-<timestamp>.json`,
alongside desktop's. Each workload is also marked on the browser's performance timeline under its
own id, readable with `performance.getEntriesByType("measure")`.

A browser can read neither `-P` properties nor the filesystem, so the Karma server stands in for
both. `benchmarks/core/karma.config.d/benchmark-server.js` serves the run config and the packaged
Synthea data, and receives the finished report. `-Pbenchmark.data.dir` and `-Pbenchmark.report.dir`
therefore do not apply on web; both paths are fixed.

## Sync against a real server

The `server` group is the only way upload is measured at all. `syncUpload` drives patch generation,
patch ordering and bundle generation through `internal` types, so an external caller can only ever
report failure — a real server is not a convenience here, it is the only route.

```bash
benchmarks/tools/start-benchmark-server.sh          # HAPI in docker, waits until it answers
./gradlew :benchmarks:core:packageBenchmarkData     # only needed for server.download
benchmarks/tools/populate-benchmark-server.sh       # ditto

./gradlew :benchmarks:core:desktopTest \
  -Pbenchmark.groups=server \
  -Pbenchmark.server=http://localhost:8080/fhir

benchmarks/tools/stop-benchmark-server.sh
```

### Loading the server

`populate-benchmark-server.sh` only matters for `server.download`; the two upload workloads
generate their own patients. It sends transaction bundles rather than one request per resource,
which at 50,000 patients is the difference between roughly 840 requests and 167,000:

```
Loaded 2,673 resources in 5s (538/s) into http://localhost:8080/fhir
```

Two properties of the corpus make that work. `--exporter.fhir.bulk_data=true` resolves every
reference to a literal `Type/id`, so entries carry no `urn:uuid` placeholders needing rewrites; and
entries are `PUT` at those ids, so the server keeps the corpus's identifiers and a repeated load
updates rather than duplicates.

Synthea re-emits an organization or practitioner once per resource referencing it — about two
thirds of those two files are repeats — and a transaction bundle carrying one id twice is rejected
outright with `HAPI-0535`. The loader drops repeats as it streams, which is why a 1,165-line
`Organization.ndjson` lands as 405 rows.

`-Pbenchmark.server` can point at any reachable FHIR server; the script is a convenience, not a
requirement. Without the flag the `server` workloads are skipped and the run says so, rather than
passing silently with nothing measured.

### On a device

The same group runs on Android, through `FhirEngineServerMacrobenchmark`. Two things differ from
desktop.

A phone resolves `localhost` to itself, so a server on the host has to be forwarded first:

```bash
adb reverse tcp:8080 tcp:8080
```

The forward belongs to the adb connection rather than to the app, so it survives the process
restart macrobenchmark does between iterations. `scripts/android_benchmark_sweep.py` sets it up
itself when `--server` names a loopback URL:

```bash
scripts/android_benchmark_sweep.py --groups server --server http://localhost:8080/fhir
```

Driving Gradle directly needs the URL forwarded to the instrumentation as well as to the build:

```bash
./gradlew :benchmarks:macro:connectedReleaseAndroidTest \
  -Pbenchmark.server=http://localhost:8080/fhir \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ohs.fhir.engine.benchmark.macro.FhirEngineServerMacrobenchmark
```

Without a URL the class is skipped by an `assumeTrue` rather than failed, so a sweep that runs
every class does not report the group as broken when the run simply did not ask for a server.

The driver app declares `INTERNET` and permits cleartext traffic, which a local HAPI needs. It is
a measurement driver and is never published, so cleartext is permitted outright rather than
through a config that would have to be edited per server.

Upload ids carry a per-process token, because macrobenchmark restarts the driver between
iterations and anything counted in memory would restart at the same value. Reusing an id is not a
failure the run would report: the server already holds the resource, so the `PUT` lands as an
update and `server.upload_creates` measures update cost under a create's name.

| Workload | What it measures |
|---|---|
| `server.upload_creates` | New resources: patch generation, bundling, POST, consolidation |
| `server.upload_updates` | Updates, so the patch generator diffs against a stored resource |
| `server.download` | Download against a real server: paging, parsing, conflict resolution |

**These numbers include the server and the network.** They are comparable only to another run
against the same server on the same machine — never to the server-free `sync` group, and never
across machines. Restart the server between comparable runs: uploads accumulate, and a server with
a million rows answers differently from an empty one.

## iOS

```bash
./gradlew :benchmarks:core:iosSimulatorArm64Test
./gradlew :benchmarks:core:iosSimulatorArm64Test -Pbenchmark.dataset=synthea
```

The report lands in `benchmarks/core/build/reports/benchmarks/ios-<timestamp>.json` like every other
platform's. A simulator shares the host filesystem, so both the report and the Synthea directory are
ordinary host paths handed to the harness through `BENCHMARK_REPORT_DIR` and `BENCHMARK_DATA_DIR`.

A Synthea run on the simulator takes about 20 minutes at `benchmark.population=10`, most of it
seeding the corpus for the fresh-database CRUD workloads.

**Simulator numbers are indicative only.** A simulator runs on the host CPU with the host's disk, so
these say whether the engine works on the platform and roughly where the costs sit — not what an
iPhone would do. A real device needs the data bundled into the test app, which this harness does not
do.

## Sample results

##### [**_SM-X135G — Samsung Galaxy Tab A9_**](https://www.gsmarena.com/samsung_galaxy_tab_a9-12558.php)

**CPU** - Octa-core (2x2.2 GHz Cortex-A76 & 6x2.0 GHz Cortex-A55) — MediaTek Helio G99

API 36 (Android 16)

*_Dataset: the android-fhir `bulk_data` corpus — 20,000 patients, 67,959 resources (Patient/Encounter/Organization/Practitioner), generated clinical mix off. Single measured iteration per workload._*

###### Data Access API results

Generated from `crud.*` workloads in `benchmarks/core`, driven per-workload by `FhirEngineCrudMacrobenchmark`; whole-dataset inserts run through the in-process harness.

| API | Average duration (ms) | Notes |
|:----|----------------------:|-------|
| create (one transaction) | ~5.80 | whole dataset: 6 m 34.5 s for 67,958 resources |
| create (single batch call) | ~8.14 | 20,000 patients in one `create(vararg)`: 2 m 42.8 s |
| create (one call per resource) | ~18.53 | whole dataset: 20 m 59.0 s |
| get | ~6.28 | 500 reads by id |
| update | ~43.66 | 500 updates in place |
| delete | ~10.44 | 500 deletes by id |

###### Search DSL API

Generated from `search.*` workloads; each figure is the average of 20 repeats of the query.

| | Population size | Average duration (ms) | Notes |
|--|----------------:|----------------------:|-------|
| patient_by_given_prefix | 20k | ~375.97 | string prefix on given name |
| patient_by_family | 20k | ~87.33 | string match on family name |
| patient_by_gender_token | 20k | ~4,358.92 | token match on gender |
| patient_by_active_token | 20k | ~1.05 | token match on active |
| patient_birthdate_range | 20k | ~3,670.69 | date range |
| patient_sort_given_asc | 20k | ~8,925.91 | sorted ascending by given name |
| patient_sort_given_desc | 20k | ~9,152.65 | sorted descending by given name |
| patient_paged | 20k | ~839.04 | paged result set |
| patient_by_organization_reference | 20k | ~0.98 | reference to organization |
| observation_by_code | 20k | ~1.83 | token match on observation code * |
| observation_by_value_quantity | 20k | ~1.71 | quantity comparison * |
| patient_two_filters_and | 20k | ~790.30 | two filters, AND |
| patient_revinclude_observation | 20k | ~8,747.96 | reverse include of observations * |
| patient_include_organization | 20k | ~8,921.67 | forward include of organizations |
| patient_has_condition | 20k | ~1.63 | chained `_has` on condition * |
| x_fhir_query_string | 20k | ~53.46 | raw x-fhir-query string |
| patient_count | 20k | ~2.75 | count only |
| patient_given_or_birthdate | 20k | ~966.65 | string OR date |
| patient_given_disjunct_values | 20k | ~813.55 | disjunct values, OR |
| encounter_by_last_updated | 20k | ~295.89 | sorted by `_lastUpdated` |
| risk_assessment_by_probability | 20k | ~10.70 | number comparison † |
| risk_assessment_probability_or_status | 20k | ~6.66 | number OR token † |

*\* the dataset carries no Observation or Condition resources, so these query empty tables*  
*† measured against 200 seeded RiskAssessments*

###### Sync API results

Against a local HAPI holding 37,884 resources, reached over `adb reverse`.

| Phase | Duration | Resources | Notes |
|:------|---------:|----------:|-------|
| Download from server | 5 m 14.0 s | 20,000 | all Patients from HAPI at `_count=100` |
| Upload (creates, transaction bundle) | 0.66 s | 100 | bundled `PUT` |
| Upload (updates, transaction bundle) | 0.71 s | 100 | bundled `PATCH` |
| In-process download, empty database | 21 m 59.8 s | 67,958 | conflict detection, indexing, write |
| In-process download over local edits | 35 m 41.7 s | 67,958 | conflict resolver runs on half the patients |

## Reading the results

Every report records the platform and a dataset `fingerprint`. **Two reports are comparable only if
both match.** Different platform, different seed, different population, or a different dataset kind
all mean the numbers are measuring different things.

Per workload the report carries the raw `samplesMillis` plus min/median/p90/max/mean/stdDev and
`medianMillisPerOp`. Prefer the median; `p90` shows how noisy the run was.

### Sanity checks

Before trusting a run:

- **The search medians must show a spread.** If they are all alike and fast, the page cache was
  never disturbed and the search numbers mean nothing. On desktop at `standard` the spread is
  roughly 0.6 ms to 108 ms.
- **Every macrobenchmark `TraceSectionMetric` must be non-zero.** A zero means the section never
  reached the trace — usually a debuggable build, a missing `<profileable>`, or a workload id that
  does not match the span name. This fails silently.
- **`isolationNote` must be null.** When set, the platform could not honour the isolation the
  workload asked for and the number is weaker than it looks.
- **Run the same profile twice.** Median-over-median drift should be well under whatever threshold
  you want to gate on. Macrobenchmark's own variance is roughly 5–10%.

## Known limitations

- **A macrobenchmark passes whether or not it measured anything.** Nothing fails a run whose trace
  sections are all zero, so check the metrics rather than the exit code; see the Android section.

- **Web has no in-process reset.** Closing the database wedges the SQLite Web Worker; not closing
  leaves it holding the exclusive OPFS handle so a reopen never completes. `FRESH_DATABASE`
  therefore degrades to `CLEAR_TABLES` on web, which the report records. A genuinely cold web
  measurement needs a page reload.
- **Upload sync needs a real server.** `syncUpload` expects response mapping types that are
  `internal`, so an external caller can only report failure. The `server` group covers upload
  against a running FHIR server; there is no in-process equivalent.
- **`js`/`wasmJs` have two pre-existing `FhirEngineImplTest` failures** unrelated to benchmarking.
