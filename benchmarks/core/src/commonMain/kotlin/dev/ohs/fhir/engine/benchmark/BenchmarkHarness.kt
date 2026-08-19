/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.fhir.engine.benchmark

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.engine.benchmark.data.Dataset
import dev.ohs.fhir.engine.benchmark.data.SyntheticDataset
import dev.ohs.fhir.engine.benchmark.workloads.Workloads

/**
 * Wires an engine, a dataset and the workload catalogue together and produces a report.
 *
 * Shared by every in-process harness (desktop, iOS, web) and by the driver app, so the setup a
 * number was produced under is identical everywhere.
 */
object BenchmarkHarness {

  suspend fun run(
    config: BenchmarkConfig = BenchmarkConfig.of(),
    dataset: Dataset = defaultDataset(config),
  ): BenchmarkReport {
    val platformContext = benchmarkPlatformContext()
    val engine = openEngine(platformContext)

    val runner =
      BenchmarkRunner(
        config = config,
        dataset = dataset,
        engine = engine,
        platformContext = platformContext,
        reopenEngine = { openEngine(platformContext) },
      )

    val report = runner.run(Workloads.byGroups(config.groups))
    emitReport(reportFileName(report), report.toJson())
    return report
  }

  /**
   * Prepares a single workload and hands back something that can be measured one iteration at a
   * time.
   *
   * Android needs this rather than [run]: macrobenchmark owns iteration control, and everything
   * before the measured block has to happen while its timer is stopped. Splitting setup from
   * measurement here keeps the Android path on the same workload objects as everywhere else instead
   * of reimplementing them.
   */
  suspend fun setUpSingle(
    workloadId: String,
    config: BenchmarkConfig = BenchmarkConfig.of(),
    dataset: Dataset = defaultDataset(config),
  ): SingleRun {
    val workload = Workloads.byId(workloadId)
    val platformContext = benchmarkPlatformContext()
    val engine = openEngine(platformContext)
    val env =
      BenchmarkEnv(
        engine = engine,
        dataset = dataset,
        platformContext = platformContext,
        reopenEngine = { openEngine(platformContext) },
      )
    workload.prepare(env)
    return SingleRun(workload, env)
  }

  /** A prepared workload. [measureOnce] is the only part that should be timed. */
  class SingleRun internal constructor(val workload: Workload, private val env: BenchmarkEnv) {

    /** Untimed per-iteration setup. Call with the harness timer stopped. */
    suspend fun beforeEach() = workload.beforeEach(env)

    /** The measured work, wrapped in the trace span named after the workload. */
    suspend fun measureOnce() = benchmarkSpan(workload.id) { workload.run(env) }

    /** Untimed per-iteration teardown. */
    suspend fun afterEach() = workload.afterEach(env)
  }

  fun defaultDataset(config: BenchmarkConfig): Dataset =
    SyntheticDataset(
      population = Profile.fromString(config.profile).population,
      seed = config.seed,
    )

  /**
   * Returns an engine on an empty database.
   *
   * Each call resets the provider and initialises against a freshly minted storage directory, so
   * [Isolation.FRESH_DATABASE] gets a genuinely cold database file rather than a cleared one.
   */
  private suspend fun openEngine(platformContext: Any): FhirEngine {
    if (FhirEngineProvider.isInitialized()) FhirEngineProvider.reset()
    deleteBenchmarkDatabase(platformContext)
    FhirEngineProvider.init(
      FhirEngineConfiguration(storageDirectory = benchmarkStorageDirectory()),
      platformContext,
    )
    return FhirEngineProvider.getInstance(platformContext)
  }

  private fun reportFileName(report: BenchmarkReport): String =
    "${report.platform.target}-${report.timestamp.replace(":", "-")}.json"
}
