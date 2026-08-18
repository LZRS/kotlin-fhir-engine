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

/**
 * How much state a workload needs torn down between iterations.
 *
 * Stated per workload rather than applied uniformly, because the choice changes what is being
 * measured. A read-heavy search wants a warm database and only its page cache disturbed; a bulk
 * insert is meaningless unless the database starts empty.
 */
enum class Isolation {
  /** Leave the database alone. For read-only workloads that do not mutate anything. */
  NONE,

  /** Delete every row, keeping the open connection and a warm page cache. */
  CLEAR_TABLES,

  /** Close, delete and reopen the database. The only honest baseline for bulk writes. */
  FRESH_DATABASE,
}

/** Everything a workload is given to do its work. */
class BenchmarkEnv(
  val engine: FhirEngine,
  val dataset: Dataset,
  val platformContext: Any,
  /** Closes the current engine and returns a new one on an empty database. */
  val reopenEngine: suspend () -> FhirEngine,
)

/**
 * One measured unit of work.
 *
 * Only [run] is timed. Anything a workload needs in place first belongs in [prepare] (once) or
 * [beforeEach] (per iteration), both untimed.
 */
interface Workload {
  /**
   * Stable identifier, e.g. `search.observation_by_code`.
   *
   * This is also the trace-section name on Android and the `performance.measure` name on web, so
   * one identifier keys the Perfetto trace, the browser timeline and the JSON report alike. Ids
   * shared with android-fhir's benchmark app are kept spelled the same so numbers line up.
   */
  val id: String

  /** `crud`, `search` or `sync`. */
  val group: String

  /**
   * Operations performed by a single [run].
   *
   * Deliberately large. Trace-based measurement on Android has a noise floor in the tens of
   * microseconds, so a workload that performs one operation measures nothing but jitter.
   */
  val opsPerIteration: Int

  val isolation: Isolation

  /** Runs once before any iteration. Untimed. */
  suspend fun prepare(env: BenchmarkEnv) {}

  /** Runs before every iteration, after isolation is applied. Untimed. */
  suspend fun beforeEach(env: BenchmarkEnv) {}

  /** The measured work. */
  suspend fun run(env: BenchmarkEnv)

  /** Runs after every iteration. Untimed. */
  suspend fun afterEach(env: BenchmarkEnv) {}
}
