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

import dev.ohs.fhir.engine.impl.FhirEngineImpl
import dev.ohs.fhir.engine.sync.AcceptRemoteConflictResolver
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * Ingesting a download, which is the write path a first sync actually takes.
 *
 * [BulkImportBenchmark] measures `insertSyncedResources`, the innermost step. `syncDownload` wraps
 * it in per-page conflict detection: for every page it reads the whole local change ledger through
 * `getAllLocalChanges`, deserializes each entry, and intersects their ids with the page's.
 *
 * [pendingChanges] sweeps the size of that ledger. Nothing in the download conflicts with it — the
 * queue holds observations and the pages carry patients — so the ledger is neither consumed nor
 * resolved, and the only thing varying is how much of it is read per page. An ingest whose cost
 * depends on a queue it never touches is the shape this is looking for.
 *
 * Every invocation downloads the same [PAGES] x [PAGE] resources. Ids repeat between invocations,
 * and `insertResource` replaces on conflict, so the corpus stays the size of one download.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
open class SyncDownloadBenchmark {

  @Param("0", "100", "1000") var pendingChanges: Int = 0

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var engine: FhirEngineImpl
  private lateinit var pages: List<List<Resource>>

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("syncdownload-$pendingChanges")
    engine = FhirEngineImpl(database.database)
    pages =
      (0 until PAGES).map { page ->
        (0 until PAGE).map { EngineBenchmarkDatabase.patient(page * PAGE + it) }
      }

    // Every arm holds the same resources; only how many still have a pending change differs.
    // Seeding fewer for the smaller arms would vary the corpus the download writes into as well,
    // and the sweep could not say which of the two it measured.
    val queued = (0 until MAX_PENDING).map { EngineBenchmarkDatabase.observation(it, MAX_PENDING) }
    database.insert(queued)
    database.discardChanges(queued.drop(pendingChanges))
    check(database.localChangeCount() == pendingChanges) {
      "the queue holds ${database.localChangeCount()} changes, expected $pendingChanges"
    }

    download()
    check(database.countOf(ResourceType.Patient) == (PAGES * PAGE).toLong()) {
      "the download wrote ${database.countOf(ResourceType.Patient)} patients, expected " +
        "${PAGES * PAGE}"
    }
    // A conflict would resolve and discard queue entries, so the sweep would measure a queue that
    // shrinks as the run goes on.
    check(database.localChangeCount() == pendingChanges) {
      "the download consumed ${pendingChanges - database.localChangeCount()} queued changes, so " +
        "the arms no longer differ only in queue size"
    }
  }

  @TearDown fun tearDown() = database.close()

  @Benchmark
  fun download() = runBlocking {
    engine.syncDownload(AcceptRemoteConflictResolver) { flowOf(*pages.toTypedArray()) }
  }

  private companion object {
    /** A plausible server page. */
    const val PAGE = 100

    /** Enough pages that a per-page cost is visible against the per-resource one. */
    const val PAGES = 10

    /** Seeded by every arm, so the corpus is constant and only the queue varies. */
    const val MAX_PENDING = 1_000
  }
}
