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
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import kotlinx.coroutines.flow.flow

/**
 * Sync workloads that need no server.
 *
 * Only the download half is reachable in-process. `syncUpload` hands request generation to the
 * caller's lambda, and the response mapping types it expects back are `internal`, so an external
 * caller can only ever report failure. Upload benchmarking needs a real server and lives behind the
 * `benchmark.server` flag; see the plan's sync section.
 */
object SyncWorkloads {

  fun all(): List<Workload> = listOf(DownloadBatch, DownloadWithConflicts, LocalChangeChurn)

  /** The full download path: conflict detection, indexing and database write. */
  private object DownloadBatch : Workload {
    override val id = "sync.download_batch"
    override val group = "sync"
    override var opsPerIteration = 1
    override val isolation = Isolation.CLEAR_TABLES

    private var batch: List<Resource> = emptyList()

    override suspend fun prepare(env: BenchmarkEnv) {
      batch = env.dataset.allResources
      opsPerIteration = batch.size
    }

    override suspend fun run(env: BenchmarkEnv) {
      env.engine.syncDownload(AcceptRemoteConflictResolver) { flow { emit(batch) } }
    }
  }

  /**
   * Half the batch already present locally and locally edited, so the resolver actually runs.
   *
   * The interesting cost here is not the resolution itself but the lookup of existing rows and
   * their local changes, which a download into an empty database never pays.
   */
  private object DownloadWithConflicts : Workload {
    override val id = "sync.download_with_conflicts"
    override val group = "sync"
    override var opsPerIteration = 1
    override val isolation = Isolation.CLEAR_TABLES

    private var batch: List<Resource> = emptyList()
    private var preexisting: List<Patient> = emptyList()

    override suspend fun prepare(env: BenchmarkEnv) {
      batch = env.dataset.allResources
      preexisting = batch.filterIsInstance<Patient>().take(batch.size / 2)
      opsPerIteration = batch.size
    }

    override suspend fun beforeEach(env: BenchmarkEnv) {
      env.engine.create(*preexisting.toTypedArray())
      revision++
      // The local edit has to be a real diff. The engine drops updates that match the stored copy
      // ("same as old resource"), which would leave these resources with no local change and hence
      // nothing for the download to conflict with — the workload would quietly become a plain
      // download.
      for (patient in preexisting) {
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
      env.engine.syncDownload(AcceptRemoteConflictResolver) { flow { emit(batch) } }
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
      targets = env.dataset.allResources.filterIsInstance<Patient>().take(CHURN_COUNT)
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
