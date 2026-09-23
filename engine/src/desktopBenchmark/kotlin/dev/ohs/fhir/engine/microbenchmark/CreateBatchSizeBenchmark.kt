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
package dev.ohs.fhir.engine.microbenchmark

import dev.ohs.fhir.model.r4.Patient
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Level

/**
 * Where the transaction boundary should go when writing many resources.
 *
 * Every arm writes the same [TOTAL] resources through `DatabaseImpl.insert`, so the scores compare
 * directly; only [batch] changes, which is how many resources share a transaction. One resource per
 * call pays a commit each time, and one call for everything pays whatever grows with the size of a
 * single transaction.
 *
 * `EngineCreateBenchmark` fixes the batch at fifty and `BulkImportBenchmark` at five hundred, so
 * neither can show where the curve flattens or whether it turns back up. The widest arm is a single
 * transaction over the whole [TOTAL], which is the shape a bulk `create(vararg)` takes.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
open class CreateBatchSizeBenchmark {

  @Param("1", "10", "100", "1000", "5000") var batch: Int = 0

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var chunks: List<List<Patient>>

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("batchsize-$batch")
    chunks = (0 until TOTAL).map(EngineBenchmarkDatabase::patient).chunked(batch)
    check(chunks.sumOf { it.size } == TOTAL) {
      "the arms must write the same $TOTAL resources, not ${chunks.sumOf { it.size }}"
    }
  }

  @TearDown fun tearDown() = database.close()

  /**
   * Emptied rather than deleted row by row: the rows and their ledger entries both have to go, or
   * every invocation writes into bigger tables than the one before it.
   */
  @TearDown(Level.Invocation)
  fun emptyDatabase() {
    database.clear()
  }

  @Benchmark
  fun createInChunks() {
    chunks.forEach { database.insert(it) }
  }

  private companion object {
    /**
     * The same total for every arm, so the score is the cost of the boundary and nothing else.
     * Large enough that the widest arm is one transaction of five thousand, which is the size the
     * device run suggested was past the useful point.
     */
    const val TOTAL = 5_000
  }
}
