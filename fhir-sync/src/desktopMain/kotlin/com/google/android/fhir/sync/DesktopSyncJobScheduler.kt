/*
 * Copyright 2026 Google LLC
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

package com.google.android.fhir.sync

import java.io.File
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [SyncJobScheduler] implementation backed by Kotlin coroutines, for desktop JVM targets.
 *
 * Sync jobs run on [Dispatchers.IO] within an internal [CoroutineScope]. The returned [Flow]
 * emits status updates for the lifetime of the job. Periodic sync re-runs every
 * [PeriodicSyncConfiguration.repeat] interval after the previous run completes.
 *
 * The scheduler does not persist state across process restarts. For persistent scheduling on
 * desktop, integrate with the OS task scheduler and call [runOneTimeSync] on launch.
 *
 * @param job The [FhirSyncJob] that provides sync components.
 */
class DesktopSyncJobScheduler(private val job: FhirSyncJob) : SyncJobScheduler {

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val _oneTimeFlow = MutableSharedFlow<CurrentSyncJobStatus>(replay = 1)
  private val _periodicFlow = MutableSharedFlow<PeriodicSyncJobStatus>(replay = 1)
  private var oneTimeJob: Job? = null
  private var periodicJob: Job? = null

  override suspend fun runOneTimeSync(
    retryConfiguration: RetryConfiguration?,
  ): Flow<CurrentSyncJobStatus> {
    oneTimeJob?.cancel()
    oneTimeJob =
      scope.launch {
        _oneTimeFlow.emit(CurrentSyncJobStatus.Enqueued)
        _oneTimeFlow.emit(CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
        val now = Clock.System.now()
        when (executeSync()) {
          is SyncJobStatus.Succeeded -> _oneTimeFlow.emit(CurrentSyncJobStatus.Succeeded(now))
          else -> _oneTimeFlow.emit(CurrentSyncJobStatus.Failed(now))
        }
      }
    return _oneTimeFlow
  }

  override suspend fun schedulePeriodicSync(
    config: PeriodicSyncConfiguration,
  ): Flow<PeriodicSyncJobStatus> {
    periodicJob?.cancel()
    var lastStatus: LastSyncJobStatus? = null
    periodicJob =
      scope.launch {
        while (isActive) {
          _periodicFlow.emit(PeriodicSyncJobStatus(lastStatus, CurrentSyncJobStatus.Enqueued))
          _periodicFlow.emit(
            PeriodicSyncJobStatus(lastStatus, CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
          )
          val now = Clock.System.now()
          when (executeSync()) {
            is SyncJobStatus.Succeeded -> {
              lastStatus = LastSyncJobStatus.Succeeded(now)
              _periodicFlow.emit(
                PeriodicSyncJobStatus(lastStatus, CurrentSyncJobStatus.Succeeded(now))
              )
            }
            else -> {
              lastStatus = LastSyncJobStatus.Failed(now)
              _periodicFlow.emit(
                PeriodicSyncJobStatus(lastStatus, CurrentSyncJobStatus.Failed(now))
              )
            }
          }
          if (isActive) delay(config.repeat.interval)
        }
      }
    return _periodicFlow
  }

  override suspend fun cancelOneTimeSync() {
    oneTimeJob?.cancel()
    oneTimeJob = null
    _oneTimeFlow.emit(CurrentSyncJobStatus.Cancelled)
  }

  override suspend fun cancelPeriodicSync() {
    periodicJob?.cancel()
    periodicJob = null
    _periodicFlow.emit(PeriodicSyncJobStatus(null, CurrentSyncJobStatus.Cancelled))
  }

  private suspend fun executeSync(): SyncJobStatus =
    fhirSyncWorkerOf(
        fhirEngine = job.getFhirEngine(),
        downloadWorkManager = job.getDownloadWorkManager(),
        conflictResolver = job.getConflictResolver(),
        uploadStrategy = job.getUploadStrategy(),
        dataStorePath = {
          File(System.getProperty("user.home"), ".fhir-engine/fhir.sync.preferences_pb")
            .absolutePath
        },
      )
      .runSync()
}
