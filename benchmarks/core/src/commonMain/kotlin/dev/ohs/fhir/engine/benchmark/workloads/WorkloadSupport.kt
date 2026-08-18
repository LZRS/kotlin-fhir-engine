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
import dev.ohs.fhir.engine.search.search
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Practitioner

/**
 * Disturbs SQLite's page cache by reading tables the workload under test does not touch.
 *
 * A port of android-fhir's `triggerChangeInSqlitePageCache`. Without it, every search after the
 * first reads pages already resident in memory and the whole search suite reports numbers that look
 * excellent and mean nothing. If the search results come back with no meaningful spread between
 * queries, suspect this first.
 */
internal suspend fun FhirEngine.evictPageCache() {
  search<Organization> { count = 1 }
  search<Practitioner> { count = 1 }
}

/** Inserts the whole dataset. Untimed setup for workloads that need populated tables. */
internal suspend fun BenchmarkEnv.seedDataset() {
  engine.create(*dataset.allResources.toTypedArray())
}

/**
 * Target ids for the per-resource CRUD workloads, in a fixed shuffled order.
 *
 * Shuffled so reads do not follow insertion order and accidentally measure sequential page access;
 * fixed so every iteration and every platform touches the same ids in the same sequence.
 */
internal fun BenchmarkEnv.crudTargetIds(limit: Int): List<String> =
  dataset.patientIds.shuffled(kotlin.random.Random(CRUD_SHUFFLE_SEED)).take(limit)

internal const val CRUD_SHUFFLE_SEED = 4242
