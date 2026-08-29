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
package dev.ohs.fhir.engine.benchmark.workloads

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.benchmark.BenchmarkEnv
import dev.ohs.fhir.engine.search.count
import dev.ohs.fhir.engine.search.search
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Practitioner
import dev.ohs.fhir.model.r4.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Disturbs SQLite's page cache by reading untouched tables; a port of android-fhir's
 * `triggerChangeInSqlitePageCache`. If the search medians show no spread, suspect this first.
 */
internal suspend fun FhirEngine.evictPageCache() {
  search<Organization> { count = 1 }
  search<Practitioner> { count = 1 }
}

/**
 * Inserts the whole dataset a chunk at a time. Untimed setup for workloads that need populated
 * tables.
 *
 * Chunked because the corpus is streamed: at 50,000 patients the parsed resources do not fit in a
 * phone's heap, so they are inserted and released rather than gathered into one call.
 */
internal suspend fun BenchmarkEnv.seedDataset() {
  val chunk = ArrayList<Resource>(SEED_CHUNK)
  dataset.resources().collect { resource ->
    chunk += resource
    if (chunk.size == SEED_CHUNK) {
      engine.create(*chunk.toTypedArray())
      chunk.clear()
    }
  }
  if (chunk.isNotEmpty()) engine.create(*chunk.toTypedArray())
}

/** Large enough that per-call overhead is amortised, small enough to stay well inside the heap. */
internal const val SEED_CHUNK = 500

/**
 * Batches a stream into fixed-size lists.
 *
 * The download half of sync takes `Flow<List<Resource>>`; without this the only way to feed it the
 * corpus is to materialise the corpus, which is what the streaming dataset exists to avoid.
 */
internal fun Flow<Resource>.chunked(size: Int): Flow<List<Resource>> = flow {
  val chunk = ArrayList<Resource>(size)
  collect { resource ->
    chunk += resource
    if (chunk.size == size) {
      emit(ArrayList(chunk))
      chunk.clear()
    }
  }
  if (chunk.isNotEmpty()) emit(chunk)
}

/**
 * Seeds only if the tables are empty.
 *
 * The read-only workloads run at [dev.ohs.fhir.engine.benchmark.Isolation.NONE] and share one
 * database, so the first of them populates it for all the rest. Seeding unconditionally in every
 * prepare() re-inserts the whole corpus once per workload, which is invisible at synthetic sizes
 * and takes minutes on real Synthea data.
 */
internal suspend fun BenchmarkEnv.seedDatasetIfEmpty() {
  if (engine.count<Patient> {} == 0L) seedDataset()
}

/** Shuffled so reads avoid sequential page access; fixed so every platform touches the same ids. */
internal fun BenchmarkEnv.crudTargetIds(limit: Int): List<String> =
  dataset.patientIds.shuffled(kotlin.random.Random(CRUD_SHUFFLE_SEED)).take(limit)

internal const val CRUD_SHUFFLE_SEED = 4242
