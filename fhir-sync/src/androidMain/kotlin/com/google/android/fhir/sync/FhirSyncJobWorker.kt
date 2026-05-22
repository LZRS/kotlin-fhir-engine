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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

internal const val SYNC_JOB_UNIQUE_WORK_NAME = "FhirSyncJobUniqueWorkName"
internal const val SYNC_JOB_MAX_RETRIES = "FhirSyncJobMaxRetries"

/**
 * Android WorkManager adapter for [FhirSyncJob].
 *
 * Extend this class, implement [createJob] to return your [FhirSyncJob], and register the
 * subclass with [AndroidSyncJobScheduler]:
 *
 * ```kotlin
 * class MyFhirSyncJobWorker(ctx: Context, params: WorkerParameters) :
 *     FhirSyncJobWorker(ctx, params) {
 *   override fun createJob() = MyFhirSyncJob()
 * }
 *
 * val scheduler = AndroidSyncJobScheduler(context, MyFhirSyncJobWorker::class.java)
 * scheduler.runOneTimeSync()
 * ```
 *
 * WorkManager instantiates this class; [createJob] is called on each execution so dependencies
 * are re-created fresh per run (safe for singleton engines).
 */
abstract class FhirSyncJobWorker(appContext: Context, params: WorkerParameters) :
  CoroutineWorker(appContext, params) {

  /** Returns the [FhirSyncJob] that provides components for this sync run. */
  abstract fun createJob(): FhirSyncJob

  override suspend fun doWork(): Result {
    val job = createJob()
    val worker =
      fhirSyncWorkerOf(
        fhirEngine = job.getFhirEngine(),
        downloadWorkManager = job.getDownloadWorkManager(),
        conflictResolver = job.getConflictResolver(),
        uploadStrategy = job.getUploadStrategy(),
        dataStorePath = {
          applicationContext.filesDir.resolve("fhir.sync.preferences_pb").absolutePath
        },
      )
    val maxRetries = inputData.getInt(SYNC_JOB_MAX_RETRIES, 0)
    return when (
      worker.runSync(
        uniqueWorkerName = inputData.getString(SYNC_JOB_UNIQUE_WORK_NAME),
        maxRetries = maxRetries,
        attempt = runAttemptCount,
      )
    ) {
      is SyncJobStatus.Succeeded -> Result.success()
      else -> if (maxRetries > runAttemptCount) Result.retry() else Result.failure()
    }
  }
}
