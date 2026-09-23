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

import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import java.io.File
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Level

/*
 * What an application waits for the first time it touches the engine.
 *
 * Every other benchmark here builds its indexer and opens its database in `@Setup`, deliberately,
 * so neither lands in a measurement of something else. That leaves the startup cost itself
 * unrecorded, even though a user pays it on every cold launch.
 *
 * Only half of it can be recorded. A cost paid once per process — loading the FHIRPath classes,
 * building the generated R4 parameter tables — is paid during warmup, by the first invocation, and
 * every scored invocation afterwards finds the work already done. No repeated-invocation harness
 * can see it; a fresh process per measurement is the only way, and JMH's forks warm up too. So
 * what [EngineStartupBenchmark] reports is per-instance construction, which is the cost of a
 * second `FhirEngineProvider.init()` rather than of a cold start, and it is worth watching because
 * a constructor that started doing real work would show up here immediately.
 *
 * [DatabaseOpenBenchmark] has no such problem: opening a database is real work every time.
 */

/**
 * Building the pieces `FhirEngineProvider` assembles before the first read or write, on a process
 * that has already built them once. See the note above on what that does and does not include.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class EngineStartupBenchmark {

  /**
   * Wrapping the generated R4 parameter tables. Nanoseconds, because the tables themselves are
   * built once for the process and this only takes a reference to them.
   */
  @Benchmark
  fun createSearchParamProvider(blackhole: Blackhole) =
    blackhole.consume(SearchParamDefinitionsProviderImpl())

  /**
   * Constructing the indexer, FHIRPath engine included. Also nanoseconds, for the same reason,
   * which is the useful part: a second engine in the same process is free to build.
   */
  @Benchmark
  fun createResourceIndexer(blackhole: Blackhole) =
    blackhole.consume(ResourceIndexer(SearchParamDefinitionsProviderImpl()))

  /**
   * Both, plus the first resource indexed through them. Runs level with
   * [ResourceIndexerBenchmark.indexRichPatient], which is the evidence that construction adds
   * nothing per instance and that the whole startup cost is the one-time initialization above.
   */
  @Benchmark
  fun coldIndexFirstResource(blackhole: Blackhole) {
    val indexer = ResourceIndexer(SearchParamDefinitionsProviderImpl())
    blackhole.consume(indexer.index(Fixtures.richPatient))
  }
}

/**
 * Opening the database, which Room does lazily on the first query rather than at construction.
 *
 * Two arms because they are different costs: a fresh directory runs the schema creation, while an
 * existing one only opens the file and reads its header. An application pays the first once and the
 * second on every launch afterwards.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
open class DatabaseOpenBenchmark {

  private lateinit var seededDirectory: File
  private lateinit var emptyDirectory: File
  private var opened: EngineBenchmarkDatabase? = null

  @Setup
  fun setUp() {
    val seeded = EngineBenchmarkDatabase.create("startup-seeded")
    seeded.seedPatients(SEEDED_ROWS)
    seededDirectory = seeded.directory
    // Closed rather than kept: the benchmark opens it again, which is the measurement.
    seeded.database.close()
    check(seededDirectory.listFiles().orEmpty().isNotEmpty()) {
      "the seeded directory holds no database file, so openSeeded would create one instead"
    }
  }

  @TearDown
  fun tearDown() {
    seededDirectory.deleteRecursively()
  }

  /** A fresh directory per invocation, created untimed so only the open itself is measured. */
  @Setup(Level.Invocation)
  fun freshDirectory() {
    emptyDirectory = File.createTempFile("bench-startup-empty-", "")
    emptyDirectory.delete()
    emptyDirectory.mkdirs()
  }

  @TearDown(Level.Invocation)
  fun closeOpened() {
    opened?.database?.close()
    opened = null
    emptyDirectory.deleteRecursively()
  }

  /** First launch after install: the schema is created before the query can be answered. */
  @Benchmark
  fun openEmptyDatabase(blackhole: Blackhole) {
    val database = EngineBenchmarkDatabase(emptyDirectory)
    opened = database
    blackhole.consume(database.localChangeCount())
  }

  /** Every launch afterwards, against a database that already holds a corpus. */
  @Benchmark
  fun openSeededDatabase(blackhole: Blackhole) {
    val database = EngineBenchmarkDatabase(seededDirectory)
    opened = database
    blackhole.consume(database.localChangeCount())
  }

  private companion object {
    /** Enough rows that the file is not empty; the open does not read them. */
    const val SEEDED_ROWS = 500
  }
}
