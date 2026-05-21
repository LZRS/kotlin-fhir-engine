/*
 * Copyright 2025-2026 Google LLC
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
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.FhirEngineProvider
import com.google.android.fhir.sync.download.DownloaderImpl
import com.google.android.fhir.sync.upload.UploadStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Android WorkManager adapter for [FhirSyncWorker].
 *
 * Extend this abstract [CoroutineWorker] and implement the abstract methods to define your
 * synchronisation behaviour. The subclass can then be used to schedule sync jobs via [Sync]:
 *
 * ```kotlin
 * Sync.oneTimeSync<MyFhirSyncWorker>(context)
 * Sync.periodicSync<MyFhirSyncWorker>(context, periodicSyncConfiguration)
 * ```
 *
 * This class bridges Android WorkManager with the platform-agnostic [FhirSyncWorker] by:
 * - forwarding all abstract method implementations to a [FhirSyncWorker] delegate, and
 * - mapping [SyncJobStatus] to WorkManager [Result] / progress [Data].
 */
abstract class AndroidFhirSyncWorker(appContext: Context, workerParams: WorkerParameters) :
  CoroutineWorker(appContext, workerParams) {

  /** Returns the [FhirEngine] instance used for interacting with the local FHIR data store. */
  abstract fun getFhirEngine(): FhirEngine

  /** Returns the [DownloadWorkManager] instance that manages the download process. */
  abstract fun getDownloadWorkManager(): DownloadWorkManager

  /**
   * Returns the [ConflictResolver] instance that defines how to handle conflicts between local and
   * remote data during synchronisation.
   */
  abstract fun getConflictResolver(): ConflictResolver

  /**
   * Returns the [UploadStrategy] instance that defines how local changes are uploaded to the
   * server.
   */
  abstract fun getUploadStrategy(): UploadStrategy

  /** Returns the [FhirDataStore] instance for persisting sync state and metadata. */
  internal open fun getFhirDataStore(): FhirDataStore =
    FhirDataStore(createDataStore(applicationContext))

  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun doWork(): Result {
    val delegate =
      object : FhirSyncWorker() {
        override fun getFhirEngine() = this@AndroidFhirSyncWorker.getFhirEngine()
        override fun getDownloadWorkManager() = this@AndroidFhirSyncWorker.getDownloadWorkManager()
        override fun getConflictResolver() = this@AndroidFhirSyncWorker.getConflictResolver()
        override fun getUploadStrategy() = this@AndroidFhirSyncWorker.getUploadStrategy()
        override fun getFhirDataStore() = this@AndroidFhirSyncWorker.getFhirDataStore()

        override suspend fun onProgress(state: SyncJobStatus) {
          setProgress(buildWorkData(state))
        }
      }

    val retries = inputData.getInt(MAX_RETRIES_ALLOWED, 0)
    val result =
      delegate.runSync(
        uniqueWorkerName = inputData.getString(UNIQUE_WORK_NAME),
        maxRetries = retries,
        attempt = runAttemptCount,
      )

    val output = buildWorkData(result)
    return when (result) {
      is SyncJobStatus.Succeeded -> Result.success(output)
      else -> if (retries > runAttemptCount) Result.retry() else Result.failure(output)
    }
  }

  private fun buildWorkData(state: SyncJobStatus): Data =
    workDataOf(
      "StateType" to state::class.java.name,
      "State" to json.encodeToString(state),
    )
}
