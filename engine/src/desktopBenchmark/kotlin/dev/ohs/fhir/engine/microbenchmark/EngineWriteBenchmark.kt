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

import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.Patient
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Level

/*
 * The write path as an application reaches it: EngineCreateBenchmark, EngineUpdateBenchmark,
 * EngineDeleteBenchmark, BulkImportBenchmark and LocalChangeReadBenchmark, all through
 * `DatabaseImpl`.
 *
 * `DatabaseImpl` differs from the `ResourceDao` the `Resource*Benchmark` classes call in two ways:
 * it wraps the whole batch in one IMMEDIATE transaction, and it records a local change per
 * resource, which serializes it a second time and walks the whole JSON tree collecting references.
 * An update pays more again — `LocalChangeDao.addUpdate` diffs the stored payload against the new
 * one and extracts the reference difference between the two versions.
 *
 * The two pull hard in opposite directions, and the transaction wins by an order of magnitude.
 * EngineCreateBenchmark.createBatch does strictly more work per resource than
 * ResourceInsertBenchmark.insertIndexed and still measures about eight times faster, because the
 * DAO path commits once per resource where this commits once per batch. That gap, not the ledger,
 * is the number worth acting on: it prices writing resources one at a time against batching them.
 * BulkImportBenchmark.importBatch is the same shape without the ledger, which is what bounds how
 * much of the remainder the ledger can account for.
 *
 * Batches rather than single resources, and for the same reason as the CRUD classes: JMH's
 * per-invocation hooks are unreliable below a millisecond.
 */

/** Creating resources locally, which records an insert per resource in the ledger. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class EngineCreateBenchmark {

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var batch: List<Patient>

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("create")
    batch = writeBatch()
  }

  @TearDown fun tearDown() = database.close()

  /**
   * Both the rows and the ledger entries, or the two tables grow for the whole run and later
   * invocations write into bigger indices than earlier ones.
   */
  @TearDown(Level.Invocation)
  fun removeWritten() {
    database.delete(batch)
    database.discardChanges(batch)
  }

  @Benchmark fun createBatch(): List<String> = database.insert(batch)
}

/** Updating resources that already exist, which diffs each against its stored payload. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class EngineUpdateBenchmark {

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var batch: List<Patient>
  private lateinit var changed: List<Patient>

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("update")
    batch = writeBatch()
    changed = batch.map { it.copy(active = FhirBoolean(value = false)) }
    // Seeded as remote, so the ledger starts empty and each invocation records exactly one update
    // per resource rather than merging into an insert left behind by the setup.
    database.seedGiven(batch)
    check(database.localChangeCount() == 0) {
      "seeding recorded ${database.localChangeCount()} local changes; the update arm would then " +
        "be measuring a merge into them"
    }
  }

  @TearDown fun tearDown() = database.close()

  @TearDown(Level.Invocation)
  fun discardRecorded() {
    database.discardChanges(batch)
  }

  @Benchmark fun updateBatch() = database.update(changed)
}

/** Deleting, which cascades across nine index tables and records a delete per resource. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class EngineDeleteBenchmark {

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var batch: List<Patient>

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("delete")
    batch = writeBatch()
  }

  @TearDown fun tearDown() = database.close()

  /** A delete consumes its rows, so they are restored untimed, along with a clean ledger. */
  @Setup(Level.Invocation)
  fun restoreRows() {
    database.discardChanges(batch)
    database.seedGiven(batch)
  }

  @Benchmark fun deleteBatch() = database.delete(batch)
}

/**
 * The download path: many resources in one transaction, with no local change recorded.
 *
 * This is what a first sync does, and the one write shape whose cost is paid in bulk rather than a
 * resource at a time. [BULK] resources an invocation, against the fifty of the other classes, so
 * compare per resource and not row to row.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
open class BulkImportBenchmark {

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var batch: List<Patient>

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("import")
    batch = (0 until BULK).map(EngineBenchmarkDatabase::patient)
  }

  @TearDown fun tearDown() = database.close()

  /** Emptied rather than deleted row by row: every invocation imports the same corpus again. */
  @TearDown(Level.Invocation)
  fun emptyDatabase() {
    database.clear()
  }

  @Benchmark fun importBatch() = database.importRemote(batch)

  private companion object {
    /**
     * A plausible download page, and enough that the per-transaction cost is not the measurement.
     */
    const val BULK = 500
  }
}

/**
 * What the upload pipeline reads before it can generate a single request.
 *
 * `FhirEngineImpl.syncUpload` fetches every pending change and then their references, both of which
 * grow with how long a device has been offline. [UploadAssemblyBenchmark] measures what is done
 * with them afterwards; this is the cost of getting them out of the database.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class LocalChangeReadBenchmark {

  @Param("50", "500") var changeCount: Int = 0

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var changeIds: List<Long>

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("localchange-$changeCount")
    // Created locally, and each one referencing a patient, so the ledger holds both a change and a
    // reference row per resource.
    database.insert(
      (0 until changeCount).map { EngineBenchmarkDatabase.observation(it, changeCount) }
    )
    check(database.localChangeCount() == changeCount) {
      "expected $changeCount pending changes, found ${database.localChangeCount()}"
    }
    changeIds = database.allLocalChanges().flatMap { it.token.ids }
    check(database.localChangeReferences(changeIds).isNotEmpty()) {
      "no reference rows were recorded, so readReferences would measure an empty lookup"
    }
  }

  @TearDown fun tearDown() = database.close()

  @Benchmark
  fun readAllLocalChanges(blackhole: Blackhole) {
    blackhole.consume(database.allLocalChanges())
  }

  @Benchmark
  fun readReferencesForChanges(blackhole: Blackhole) {
    blackhole.consume(database.localChangeReferences(changeIds))
  }
}

/** The resources every write class above writes: the same batch size as the CRUD benchmarks. */
private fun writeBatch(): List<Patient> =
  (0 until CrudFixture.BATCH).map(EngineBenchmarkDatabase::patient)
