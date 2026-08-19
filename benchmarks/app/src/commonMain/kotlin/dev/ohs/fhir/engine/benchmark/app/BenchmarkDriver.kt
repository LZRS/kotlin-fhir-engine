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
package dev.ohs.fhir.engine.benchmark.app

import dev.ohs.fhir.engine.benchmark.BenchmarkHarness

/**
 * Runs what a [BenchmarkRequest] asks for.
 *
 * Two modes, because the two consumers need different things. A full run drives the whole catalogue
 * itself and writes a report, which is what desktop and web want. A single-workload run only
 * prepares state and exposes one measured call, which is what Android needs: macrobenchmark owns
 * the iteration loop and everything outside the measured block must happen with its timer stopped.
 */
object BenchmarkDriver {

  /** Runs whole groups and emits a report. Returns a one-line summary for the status view. */
  suspend fun runAll(request: BenchmarkRequest): String {
    val report = BenchmarkHarness.run(request.toConfig())
    val failed = report.results.count { it.error != null }
    return "ran ${report.results.size} workloads on ${report.platform.target}, $failed failed"
  }

  /** Prepares one workload. The caller decides when to measure. */
  suspend fun prepareSingle(request: BenchmarkRequest): BenchmarkHarness.SingleRun {
    val workloadId = requireNotNull(request.workloadId) { "prepareSingle needs a workload id." }
    return BenchmarkHarness.setUpSingle(workloadId, request.toConfig())
  }
}
