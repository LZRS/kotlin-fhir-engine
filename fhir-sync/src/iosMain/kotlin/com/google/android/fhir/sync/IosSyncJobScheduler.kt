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

import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTask
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * [SyncJobScheduler] implementation backed by iOS [BGTaskScheduler].
 *
 * **Setup (required before first use):**
 * 1. Declare [oneTimeSyncIdentifier] and [periodicSyncIdentifier] in `Info.plist` under
 *    `BGTaskSchedulerPermittedIdentifiers`.
 * 2. Call [register] before the app finishes launching, e.g. from
 *    `application(_:didFinishLaunchingWithOptions:)`.
 *
 * One-time sync uses a [BGProcessingTask] (can run for minutes, requires network). Periodic sync
 * uses a [BGAppRefreshTask] that re-schedules itself after each run.
 *
 * @param job The [FhirSyncJob] that provides sync components.
 * @param oneTimeSyncIdentifier BGProcessingTask identifier registered in Info.plist.
 * @param periodicSyncIdentifier BGAppRefreshTask identifier registered in Info.plist.
 */
class IosSyncJobScheduler(
  private val job: FhirSyncJob,
  private val oneTimeSyncIdentifier: String,
  private val periodicSyncIdentifier: String,
) : SyncJobScheduler {

  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private val _oneTimeFlow = MutableSharedFlow<CurrentSyncJobStatus>(replay = 1)
  private val _periodicFlow = MutableSharedFlow<PeriodicSyncJobStatus>(replay = 1)
  private var periodicInterval: Duration? = null
  private var lastPeriodicStatus: LastSyncJobStatus? = null

  /**
   * Registers BGTask handlers with the system. Must be called before the app finishes launching.
   *
   * Handlers are registered once for the lifetime of the app process. Calling [register] again
   * after the first call has no effect (iOS ignores duplicate registrations).
   */
  fun register() {
    BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
      identifier = oneTimeSyncIdentifier,
      usingQueue = null,
    ) { task -> task?.let { handleOneTimeTask(it as BGProcessingTask) } }

    BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
      identifier = periodicSyncIdentifier,
      usingQueue = null,
    ) { task -> task?.let { handlePeriodicTask(it as BGAppRefreshTask) } }
  }

  override suspend fun runOneTimeSync(
    retryConfiguration: RetryConfiguration?,
  ): Flow<CurrentSyncJobStatus> {
    submitOneTimeRequest()
    return _oneTimeFlow
  }

  override suspend fun schedulePeriodicSync(
    config: PeriodicSyncConfiguration,
  ): Flow<PeriodicSyncJobStatus> {
    periodicInterval = config.repeat.interval
    submitPeriodicRequest()
    return _periodicFlow
  }

  override suspend fun cancelOneTimeSync() {
    BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(oneTimeSyncIdentifier)
  }

  override suspend fun cancelPeriodicSync() {
    periodicInterval = null
    BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(periodicSyncIdentifier)
  }

  private fun submitOneTimeRequest() {
    val request = BGProcessingTaskRequest(identifier = oneTimeSyncIdentifier)
    request.requiresNetworkConnectivity = true
    request.requiresExternalPower = false
    BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
  }

  private fun submitPeriodicRequest(delaySeconds: Double = 0.0) {
    val request = BGAppRefreshTaskRequest(identifier = periodicSyncIdentifier)
    if (delaySeconds > 0) {
      request.earliestBeginDate =
        platform.Foundation.NSDate.dateWithTimeIntervalSinceNow(delaySeconds)
    }
    BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
  }

  private fun handleOneTimeTask(task: BGProcessingTask) {
    val syncJob = scope.launch {
      _oneTimeFlow.emit(CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
      val now = Clock.System.now()
      when (executeSync()) {
        is SyncJobStatus.Succeeded -> {
          _oneTimeFlow.emit(CurrentSyncJobStatus.Succeeded(now))
          task.setTaskCompleted(true)
        }
        else -> {
          _oneTimeFlow.emit(CurrentSyncJobStatus.Failed(now))
          task.setTaskCompleted(false)
        }
      }
    }
    task.expirationHandler = {
      syncJob.cancel()
      task.setTaskCompleted(false)
    }
  }

  private fun handlePeriodicTask(task: BGAppRefreshTask) {
    val syncJob = scope.launch {
      _periodicFlow.emit(
        PeriodicSyncJobStatus(lastPeriodicStatus, CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
      )
      val now = Clock.System.now()
      when (executeSync()) {
        is SyncJobStatus.Succeeded -> {
          lastPeriodicStatus = LastSyncJobStatus.Succeeded(now)
          _periodicFlow.emit(
            PeriodicSyncJobStatus(lastPeriodicStatus, CurrentSyncJobStatus.Succeeded(now))
          )
          task.setTaskCompleted(true)
        }
        else -> {
          lastPeriodicStatus = LastSyncJobStatus.Failed(now)
          _periodicFlow.emit(
            PeriodicSyncJobStatus(lastPeriodicStatus, CurrentSyncJobStatus.Failed(now))
          )
          task.setTaskCompleted(false)
        }
      }
      periodicInterval?.let { submitPeriodicRequest(it.inWholeSeconds.toDouble()) }
    }
    task.expirationHandler = {
      syncJob.cancel()
      task.setTaskCompleted(false)
      // Re-schedule so the periodic chain isn't broken by an expiration
      periodicInterval?.let { submitPeriodicRequest(it.inWholeSeconds.toDouble()) }
    }
  }

  private suspend fun executeSync(): SyncJobStatus =
    fhirSyncWorkerOf(
        fhirEngine = job.getFhirEngine(),
        downloadWorkManager = job.getDownloadWorkManager(),
        conflictResolver = job.getConflictResolver(),
        uploadStrategy = job.getUploadStrategy(),
        dataStorePath = ::iosDataStorePath,
      )
      .runSync()
}

@OptIn(ExperimentalForeignApi::class)
private fun iosDataStorePath(): String {
  val dir =
    NSFileManager.defaultManager.URLForDirectory(
      directory = NSDocumentDirectory,
      inDomain = NSUserDomainMask,
      appropriateForURL = null,
      create = false,
      error = null,
    )
  return requireNotNull(dir).path + "/fhir.sync.preferences_pb"
}
