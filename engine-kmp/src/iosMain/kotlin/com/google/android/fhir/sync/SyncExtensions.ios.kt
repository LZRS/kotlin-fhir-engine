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

import com.google.android.fhir.FhirEngineProvider
import com.google.android.fhir.sync.upload.UploadStrategy
import kotlinx.coroutines.flow.Flow

private fun buildIosScheduler(
  downloadWorkManager: DownloadWorkManager,
  conflictResolver: ConflictResolver,
  uploadStrategy: UploadStrategy,
): IosSyncScheduler {
  val fhirEngine = FhirEngineProvider.getInstance()
  val dataSource =
    checkNotNull(FhirEngineProvider.getDataSource()) {
      "FhirEngineConfiguration.ServerConfiguration is not set. " +
        "Call FhirEngineProvider.init() with a ServerConfiguration."
    }
  val fhirDataStore = FhirDataStore(createDataStore())
  return IosSyncScheduler(fhirEngine, dataSource, downloadWorkManager, conflictResolver, uploadStrategy, fhirDataStore)
}

/**
 * Runs a one-time sync on iOS and returns a [Flow] of [CurrentSyncJobStatus].
 *
 * Usage:
 * ```
 * scope.launch {
 *   Sync.oneTimeSync(downloadWorkManager, conflictResolver, uploadStrategy).collect { status ->
 *     // update UI
 *   }
 * }
 * ```
 */
suspend fun Sync.oneTimeSync(
  downloadWorkManager: DownloadWorkManager,
  conflictResolver: ConflictResolver,
  uploadStrategy: UploadStrategy,
  retryConfiguration: RetryConfiguration? = defaultRetryConfiguration,
): Flow<CurrentSyncJobStatus> =
  oneTimeSync(buildIosScheduler(downloadWorkManager, conflictResolver, uploadStrategy), retryConfiguration)

/**
 * Schedules a repeating sync on iOS and returns a [Flow] of [PeriodicSyncJobStatus].
 *
 * The flow drives a coroutine loop; cancel the collecting scope to stop.
 */
suspend fun Sync.periodicSync(
  downloadWorkManager: DownloadWorkManager,
  conflictResolver: ConflictResolver,
  uploadStrategy: UploadStrategy,
  config: PeriodicSyncConfiguration,
): Flow<PeriodicSyncJobStatus> =
  periodicSync(buildIosScheduler(downloadWorkManager, conflictResolver, uploadStrategy), config)
