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

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.sync.upload.UploadStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * iOS implementation of [SyncScheduler] that drives sync directly via [FhirSyncCore] coroutines.
 *
 * No background-task framework is used; callers own the lifecycle by cancelling the collecting
 * coroutine or by calling [cancelOneTimeSync] / [cancelPeriodicSync].
 */
class IosSyncScheduler(
  private val fhirEngine: FhirEngine,
  private val dataSource: DataSource,
  private val downloadWorkManager: DownloadWorkManager,
  private val conflictResolver: ConflictResolver,
  private val uploadStrategy: UploadStrategy,
  private val fhirDataStore: FhirDataStore,
) : SyncScheduler {

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var oneTimeSyncJob: Job? = null
  private var periodicSyncJob: Job? = null

  /**
   * Runs a one-time sync immediately and returns a [Flow] of [CurrentSyncJobStatus].
   *
   * Respects [retryConfiguration]: on failure, retries up to [RetryConfiguration.maxRetries] times
   * with the configured backoff delay between attempts.
   */
  override suspend fun runOneTimeSync(
    retryConfiguration: RetryConfiguration?,
  ): Flow<CurrentSyncJobStatus> {
    val channel = Channel<CurrentSyncJobStatus>(Channel.BUFFERED)
    oneTimeSyncJob?.cancel()
    oneTimeSyncJob = scope.launch {
      val maxRetries = retryConfiguration?.maxRetries ?: 0
      var attempt = 0
      try {
        channel.send(CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
        var result: SyncJobStatus
        do {
          result = buildSyncCore().execute(
            workerName = null,
            onProgress = { status -> channel.trySend(CurrentSyncJobStatus.Running(status)) },
          )
          if (result is SyncJobStatus.Failed && attempt < maxRetries) {
            attempt++
            delay(retryConfiguration!!.backoffCriteria.backoffDelay)
          } else {
            break
          }
        } while (true)
        when (result) {
          is SyncJobStatus.Succeeded ->
            channel.send(CurrentSyncJobStatus.Succeeded(result.timestamp))
          is SyncJobStatus.Failed ->
            channel.send(CurrentSyncJobStatus.Failed(result.timestamp))
          else -> {}
        }
      } catch (_: CancellationException) {
        channel.trySend(CurrentSyncJobStatus.Cancelled)
      } finally {
        channel.close()
      }
    }
    return channel.receiveAsFlow()
  }

  /**
   * Schedules a repeating sync driven by a coroutine loop and returns a [Flow] of
   * [PeriodicSyncJobStatus].
   *
   * Each iteration waits [PeriodicSyncConfiguration.repeat] interval before the next run.
   * Cancel the collecting coroutine (or call [cancelPeriodicSync]) to stop.
   */
  override suspend fun schedulePeriodicSync(
    config: PeriodicSyncConfiguration,
  ): Flow<PeriodicSyncJobStatus> {
    val channel = Channel<PeriodicSyncJobStatus>(Channel.BUFFERED)
    var lastStatus: LastSyncJobStatus? = null
    periodicSyncJob?.cancel()
    periodicSyncJob = scope.launch {
      while (isActive) {
        try {
          channel.send(
            PeriodicSyncJobStatus(lastStatus, CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
          )
          val result = buildSyncCore().execute(
            workerName = null,
            onProgress = { status ->
              channel.trySend(
                PeriodicSyncJobStatus(lastStatus, CurrentSyncJobStatus.Running(status))
              )
            },
          )
          lastStatus = when (result) {
            is SyncJobStatus.Succeeded -> LastSyncJobStatus.Succeeded(result.timestamp)
            is SyncJobStatus.Failed -> LastSyncJobStatus.Failed(result.timestamp)
            else -> null
          }
          val currentStatus = when (result) {
            is SyncJobStatus.Succeeded -> CurrentSyncJobStatus.Succeeded(result.timestamp)
            is SyncJobStatus.Failed -> CurrentSyncJobStatus.Failed(result.timestamp)
            else -> CurrentSyncJobStatus.Cancelled
          }
          channel.send(PeriodicSyncJobStatus(lastStatus, currentStatus))
          delay(config.repeat.interval)
        } catch (_: CancellationException) {
          channel.trySend(PeriodicSyncJobStatus(lastStatus, CurrentSyncJobStatus.Cancelled))
          channel.close()
          return@launch
        }
      }
    }
    return channel.receiveAsFlow()
  }

  override suspend fun cancelOneTimeSync() {
    oneTimeSyncJob?.cancel()
    oneTimeSyncJob = null
  }

  override suspend fun cancelPeriodicSync() {
    periodicSyncJob?.cancel()
    periodicSyncJob = null
  }

  private fun buildSyncCore() = FhirSyncCore(
    fhirEngine = fhirEngine,
    dataSource = dataSource,
    downloadWorkManager = downloadWorkManager,
    conflictResolver = conflictResolver,
    uploadStrategy = uploadStrategy,
    fhirDataStore = fhirDataStore,
  )
}
