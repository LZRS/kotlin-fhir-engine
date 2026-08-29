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

import dev.ohs.fhir.engine.benchmark.BenchmarkEnv
import dev.ohs.fhir.engine.benchmark.Isolation
import dev.ohs.fhir.engine.benchmark.Workload
import dev.ohs.fhir.engine.delete
import dev.ohs.fhir.engine.get
import dev.ohs.fhir.engine.sync.AcceptRemoteConflictResolver
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.String as FhirString
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList

/**
 * Server-free sync workloads. Only download is reachable in-process: `syncUpload` expects response
 * mapping types that are `internal`, so an external caller can only ever report failure.
 */
object SyncWorkloads {

  /** Page size for the simulated download. Matches what a server would realistically return. */
  private const val DOWNLOAD_CHUNK = 500

  fun all(): List<Workload> = listOf(DownloadBatch, DownloadWithConflicts, LocalChangeChurn)

  /** The full download path: conflict detection, indexing and database write. */
  private object DownloadBatch : Workload {
    override val id = "sync.download_batch"
    override val group = "sync"
    override var opsPerIteration = 1
    override val isolation = Isolation.CLEAR_TABLES

    override suspend fun prepare(env: BenchmarkEnv) {
      opsPerIteration = env.dataset.resourceCount
    }

    override suspend fun run(env: BenchmarkEnv) {
      // Chunked from the stream rather than one list: a real download arrives in pages anyway,
      // and the whole corpus does not fit in memory at benchmark populations.
      env.engine.syncDownload(AcceptRemoteConflictResolver) {
        env.dataset.resources().chunked(DOWNLOAD_CHUNK)
      }
    }
  }

  /**
   * Half the batch present locally and edited, so the resolver runs. The cost of interest is the
   * lookup of existing rows and their local changes, which a download into an empty database skips.
   */
  private object DownloadWithConflicts : Workload {
    override val id = "sync.download_with_conflicts"
    override val group = "sync"
    override var opsPerIteration = 1
    override val isolation = Isolation.CLEAR_TABLES

    private var half = 0

    override suspend fun prepare(env: BenchmarkEnv) {
      half = env.dataset.population / 2
      opsPerIteration = env.dataset.resourceCount
    }

    override suspend fun beforeEach(env: BenchmarkEnv) {
      // Created and edited one at a time rather than gathered first: half a 50,000-patient corpus
      // held as objects is already past the heap. Each patient still ends with a local change,
      // which is all the resolver needs.
      revision++
      var seeded = 0
      env.dataset
        .resources()
        .filterIsInstance<Patient>()
        .takeWhile { seeded < half }
        .collect { patient ->
          seeded++
          env.engine.create(patient)
          // Without a real diff the engine drops the update, leaving nothing to conflict with and
          // quietly turning this into a plain download.
          env.engine.update(
            env.engine
              .get<Patient>(patient.id!!)
              .copy(
                name =
                  listOf(
                    HumanName(
                      family = FhirString(value = "LocalEdit$revision"),
                      given = listOf(FhirString(value = "Benchmark")),
                    ),
                  ),
              ),
          )
        }
    }

    private var revision = 0

    override suspend fun run(env: BenchmarkEnv) {
      env.engine.syncDownload(AcceptRemoteConflictResolver) {
        env.dataset.resources().chunked(DOWNLOAD_CHUNK)
      }
    }
  }

  /** Local-change accumulation: the table upload has to walk later. */
  private object LocalChangeChurn : Workload {
    override val id = "sync.local_change_churn"
    override val group = "sync"
    override var opsPerIteration = 1
    override val isolation = Isolation.CLEAR_TABLES

    private var targets: List<Patient> = emptyList()

    override suspend fun prepare(env: BenchmarkEnv) {
      targets = env.dataset.resources().filterIsInstance<Patient>().take(CHURN_COUNT).toList()
      // One create, two updates and a delete for every tenth resource.
      opsPerIteration = targets.size * 3 + targets.size / 10
    }

    override suspend fun run(env: BenchmarkEnv) {
      env.engine.create(*targets.toTypedArray())
      churn++
      repeat(2) { pass ->
        for (patient in targets) {
          env.engine.update(
            patient.copy(
              name =
                listOf(
                  HumanName(
                    family = FhirString(value = "Churn$churn-$pass"),
                    given = listOf(FhirString(value = "Benchmark")),
                  ),
                ),
            ),
          )
        }
      }
      for ((index, patient) in targets.withIndex()) {
        if (index % 10 == 0) env.engine.delete<Patient>(patient.id!!)
      }
    }

    private var churn = 0

    private const val CHURN_COUNT = 200
  }
}
