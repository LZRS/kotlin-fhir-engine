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
