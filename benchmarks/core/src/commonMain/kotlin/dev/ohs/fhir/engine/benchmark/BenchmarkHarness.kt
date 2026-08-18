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
