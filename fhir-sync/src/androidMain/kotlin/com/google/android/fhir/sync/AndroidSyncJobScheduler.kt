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

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.BackoffPolicy as WMBackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType as WMNetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan

/**
 * [SyncJobScheduler] implementation backed by Android WorkManager.
 *
 * WorkManager handles constraint enforcement, Doze-mode compatibility, and reliable retry. Each
 * sync run is executed by the provided [FhirSyncJobWorker] subclass.
 *
 * Usage:
 * ```kotlin
 * val scheduler = AndroidSyncJobScheduler(context, MyFhirSyncJobWorker::class.java)
 *
 * // One-time
 * scheduler.runOneTimeSync().collect { status -> ... }
 *
 * // Periodic
 * scheduler.schedulePeriodicSync(periodicSyncConfiguration).collect { status -> ... }
 * ```
 *
 * @param context Application context.
 * @param workerClass The [FhirSyncJobWorker] subclass to instantiate for each sync run.
 */
class AndroidSyncJobScheduler<W : FhirSyncJobWorker>(
  private val context: Context,
  private val workerClass: Class<W>,
) : SyncJobScheduler {

  private val workManager get() = WorkManager.getInstance(context)
  private val baseWorkName = workerClass.name

  @OptIn(ExperimentalCoroutinesApi::class)
  override suspend fun runOneTimeSync(
    retryConfiguration: RetryConfiguration?,
  ): Flow<CurrentSyncJobStatus> {
    val workName = "$baseWorkName-oneTimeSync"
    workManager.enqueueUniqueWork(
      workName,
      ExistingWorkPolicy.KEEP,
      buildOneTimeRequest(retryConfiguration, workName),
    )
    return workManager
      .getWorkInfosForUniqueWorkLiveData(workName)
      .asFlow()
      .flatMapConcat { it.asFlow() }
      .mapNotNull { toCurrentSyncJobStatus(it) }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  override suspend fun schedulePeriodicSync(
    config: PeriodicSyncConfiguration,
  ): Flow<PeriodicSyncJobStatus> {
    val workName = "$baseWorkName-periodicSync"
    workManager.enqueueUniquePeriodicWork(
      workName,
      ExistingPeriodicWorkPolicy.KEEP,
      buildPeriodicRequest(config, workName),
    )
    return workManager
      .getWorkInfosForUniqueWorkLiveData(workName)
      .asFlow()
      .flatMapConcat { it.asFlow() }
      // scan carries lastSyncJobStatus across periodic WorkManager runs
      .scan(PeriodicSyncJobStatus(null, CurrentSyncJobStatus.Enqueued)) { prev, info ->
        toPeriodicSyncJobStatus(info, prev.lastSyncJobStatus) ?: prev
      }
  }

  override suspend fun cancelOneTimeSync() {
    workManager.cancelUniqueWork("$baseWorkName-oneTimeSync")
  }

  override suspend fun cancelPeriodicSync() {
    workManager.cancelUniqueWork("$baseWorkName-periodicSync")
  }

  private fun buildOneTimeRequest(
    retryConfiguration: RetryConfiguration?,
    workName: String,
  ): OneTimeWorkRequest {
    val inputData = Data.Builder().putString(SYNC_JOB_UNIQUE_WORK_NAME, workName)
    val builder = OneTimeWorkRequest.Builder(workerClass)
    retryConfiguration?.let {
      builder.setBackoffCriteria(
        it.backoffCriteria.backoffPolicy.toWMPolicy(),
        it.backoffCriteria.backoffDelay.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
      )
      inputData.putInt(SYNC_JOB_MAX_RETRIES, it.maxRetries)
    }
    return builder.setInputData(inputData.build()).build()
  }

  private fun buildPeriodicRequest(
    config: PeriodicSyncConfiguration,
    workName: String,
  ): PeriodicWorkRequest {
    val inputData = Data.Builder().putString(SYNC_JOB_UNIQUE_WORK_NAME, workName)
    val builder =
      PeriodicWorkRequest.Builder(
          workerClass,
          config.repeat.interval.inWholeMilliseconds,
          TimeUnit.MILLISECONDS,
        )
        .setConstraints(config.syncConstraints.toWMConstraints())
    config.retryConfiguration?.let {
      builder.setBackoffCriteria(
        it.backoffCriteria.backoffPolicy.toWMPolicy(),
        it.backoffCriteria.backoffDelay.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
      )
      inputData.putInt(SYNC_JOB_MAX_RETRIES, it.maxRetries)
    }
    return builder.setInputData(inputData.build()).build()
  }

  private fun toCurrentSyncJobStatus(info: WorkInfo): CurrentSyncJobStatus? =
    when (info.state) {
      WorkInfo.State.ENQUEUED -> CurrentSyncJobStatus.Enqueued
      WorkInfo.State.RUNNING -> CurrentSyncJobStatus.Running(SyncJobStatus.Started())
      WorkInfo.State.SUCCEEDED -> CurrentSyncJobStatus.Succeeded(Clock.System.now())
      WorkInfo.State.FAILED -> CurrentSyncJobStatus.Failed(Clock.System.now())
      WorkInfo.State.CANCELLED -> CurrentSyncJobStatus.Cancelled
      WorkInfo.State.BLOCKED -> CurrentSyncJobStatus.Blocked
      else -> null
    }

  private fun toPeriodicSyncJobStatus(
    info: WorkInfo,
    lastStatus: LastSyncJobStatus?,
  ): PeriodicSyncJobStatus? {
    val now = Clock.System.now()
    return when (info.state) {
      WorkInfo.State.ENQUEUED ->
        PeriodicSyncJobStatus(lastStatus, CurrentSyncJobStatus.Enqueued)
      WorkInfo.State.RUNNING ->
        PeriodicSyncJobStatus(lastStatus, CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
      WorkInfo.State.SUCCEEDED ->
        PeriodicSyncJobStatus(LastSyncJobStatus.Succeeded(now), CurrentSyncJobStatus.Succeeded(now))
      WorkInfo.State.FAILED ->
        PeriodicSyncJobStatus(LastSyncJobStatus.Failed(now), CurrentSyncJobStatus.Failed(now))
      WorkInfo.State.CANCELLED ->
        PeriodicSyncJobStatus(lastStatus, CurrentSyncJobStatus.Cancelled)
      WorkInfo.State.BLOCKED ->
        PeriodicSyncJobStatus(lastStatus, CurrentSyncJobStatus.Blocked)
      else -> null
    }
  }
}

private fun BackoffPolicy.toWMPolicy(): WMBackoffPolicy =
  when (this) {
    BackoffPolicy.EXPONENTIAL -> WMBackoffPolicy.EXPONENTIAL
    BackoffPolicy.LINEAR -> WMBackoffPolicy.LINEAR
  }

private fun SyncConstraints.toWMConstraints(): Constraints =
  Constraints.Builder()
    .setRequiredNetworkType(
      when (requiredNetworkType) {
        NetworkType.NOT_REQUIRED -> WMNetworkType.NOT_REQUIRED
        NetworkType.CONNECTED -> WMNetworkType.CONNECTED
        NetworkType.UNMETERED -> WMNetworkType.UNMETERED
        NetworkType.NOT_ROAMING -> WMNetworkType.NOT_ROAMING
        NetworkType.METERED -> WMNetworkType.METERED
      }
    )
    .setRequiresBatteryNotLow(requiresBatteryNotLow)
    .setRequiresCharging(requiresCharging)
    .setRequiresDeviceIdle(requiresDeviceIdle)
    .setRequiresStorageNotLow(requiresStorageNotLow)
    .build()
