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
import dev.ohs.fhir.engine.benchmark.data.Dataset
import kotlin.time.TimeSource

/**
 * Runs workloads in-process and summarises them.
 *
 * Used by the desktop, iOS and web harnesses. Android goes through macrobenchmark instead, which
 * owns its own iteration control and reads the spans out of a trace; both paths execute the very
 * same [Workload] objects.
 */
class BenchmarkRunner(
  private val config: BenchmarkConfig,
  private val dataset: Dataset,
  private var engine: FhirEngine,
  private val platformContext: Any,
  private val reopenEngine: suspend () -> FhirEngine,
) {

  suspend fun run(workloads: List<Workload>): BenchmarkReport {
    val results = workloads.map { runWorkload(it) }
    return BenchmarkReport(
      timestamp = nowIso8601(),
      platform = platformDescriptor(),
      config = config,
      dataset = dataset.manifest(),
      results = results,
    )
  }

  private suspend fun runWorkload(workload: Workload): WorkloadResult {
    val (isolation, note) = resolveIsolation(workload.isolation)
    val samples = mutableListOf<Double>()
    return try {
      var env = newEnv()
      workload.prepare(env)

      repeat(config.warmupIterations + config.measuredIterations) { iteration ->
        env = applyIsolation(isolation, env)
        workload.beforeEach(env)

        val start = TimeSource.Monotonic.markNow()
        benchmarkSpan(workload.id) { workload.run(env) }
        val elapsed = start.elapsedNow()

        workload.afterEach(env)
        if (iteration >= config.warmupIterations) {
          samples += elapsed.inWholeMicroseconds / 1000.0
        }
      }

      val statistics = Statistics.of(samples)
      WorkloadResult(
        id = workload.id,
        group = workload.group,
        opsPerIteration = workload.opsPerIteration,
        isolationApplied = isolation.name.lowercase(),
        isolationNote = note,
        samplesMillis = samples,
        statistics = statistics,
        medianMillisPerOp = statistics.median / workload.opsPerIteration,
      )
    } catch (e: Throwable) {
      // One broken workload must not cost the whole run. The report records the failure so a
      // missing number is visible rather than silently absent.
      WorkloadResult(
        id = workload.id,
        group = workload.group,
        opsPerIteration = workload.opsPerIteration,
        isolationApplied = isolation.name.lowercase(),
        isolationNote = note,
        samplesMillis = samples,
        statistics = if (samples.isEmpty()) EMPTY_STATISTICS else Statistics.of(samples),
        medianMillisPerOp = 0.0,
        error = "${e::class.simpleName}: ${e.message}",
      )
    }
  }

  /** Degrades [requested] where the platform cannot honour it, and says so. */
  private fun resolveIsolation(requested: Isolation): Pair<Isolation, String?> =
    if (requested == Isolation.FRESH_DATABASE && !supportsFreshDatabase()) {
      Isolation.CLEAR_TABLES to
        "Requested fresh_database but this platform cannot reopen the database in-process, so the " +
          "database was cleared instead and the page cache stayed warm. Treat as a lower bound."
    } else {
      requested to null
    }

  private suspend fun applyIsolation(isolation: Isolation, env: BenchmarkEnv): BenchmarkEnv =
    when (isolation) {
      Isolation.NONE -> env
      Isolation.CLEAR_TABLES -> env.also { it.engine.clearDatabase() }
      Isolation.FRESH_DATABASE -> {
        engine = reopenEngine()
        newEnv()
      }
    }

  private fun newEnv() =
    BenchmarkEnv(
      engine = engine,
      dataset = dataset,
      platformContext = platformContext,
      reopenEngine = reopenEngine,
    )

  private companion object {
    val EMPTY_STATISTICS = Statistics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
  }
}
