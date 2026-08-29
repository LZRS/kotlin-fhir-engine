# Resuming the 50k Android benchmark run

Committed at `5ec03ca9` on `benchmarks-engine-reset`. Tree clean, all tests green.

## Where this stopped

Everything needed to *run* 50k is in. What has not happened is the 50k run itself,
because the corpus on disk is currently 500 patients.

## Next step, in order

1. Regenerate the corpus (~30 min, deterministic — seed 20260819):
   ./gradlew :benchmarks:core:packageBenchmarkData -Pbenchmark.population=50000 -Pbenchmark.regenerate

2. Triage sweep first — one iteration each, finds what survives:
   python3 scripts/android_benchmark_sweep.py --population 50000 --triage-only

3. Full sweep over the survivors:
   python3 scripts/android_benchmark_sweep.py --population 50000 --reuse-corpus

Add --server http://localhost:8080/fhir (after benchmarks/tools/start-benchmark-server.sh)
to include the three server workloads.

## The one thing left unverified

Two workloads back to back, where the second should reuse the seeded database and the
cached scan. The build for that run failed on a stale compile error which is now fixed,
so the test was never actually performed. Run it before trusting the time estimates:

    adb uninstall dev.ohs.fhir.engine.benchmark.app
    python3 scripts/android_benchmark_sweep.py --only search.patient_by_family \
      search.patient_by_gender_token --population 500 --reuse-corpus --triage-only

Expect: the first workload pays parse + seed, the second is much faster. If the second
is just as slow, the cache or the leaveApksInstalledAfterRun flag is not working, and
the sweep time estimates below are wrong.

## Measured numbers (Samsung SM-X135G, Android 16 / API 36)

- insert: 152 resources/second
- parse:  0.90 MB/s of NDJSON
- heap:   256 MB normal, 512 MB with largeHeap (now set)
- APK assets deflate 14x

At 50k: corpus ~167k resources + 500k generated (8 observations + 2 conditions per
patient) = ~667k rows, about 73 minutes for the one-time seed.

## Decisions taken, worth revisiting

- Synthea runs the pregnancy module only. The full module set gives 131 resources per
  patient — ~5.4 GB and ~13.6 hours to insert at 50k. Observations and conditions are
  generated in code instead (AugmentedDataset), which makes per-patient volume a dial.
- The mix is 8 observations + 2 conditions, hardcoded in ClinicalMix(). No Gradle flag
  for it yet. Both are types a workload actually queries; ballast types were left out.
- A database snapshot mechanism was written and then deleted once
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true turned out to solve the
  same problem in one flag.
