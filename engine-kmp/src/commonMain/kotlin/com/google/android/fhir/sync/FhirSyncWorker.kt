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

import co.touchlab.kermit.Logger
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.FhirEngineProvider
import com.google.android.fhir.sync.download.DownloaderImpl
import com.google.android.fhir.sync.upload.UploadStrategy
import com.google.android.fhir.sync.upload.Uploader
import com.google.android.fhir.sync.upload.patch.PatchGeneratorFactory
import com.google.android.fhir.sync.upload.request.UploadRequestGeneratorFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Platform-agnostic base for FHIR data synchronization between a local database and remote server.
 *
 * Extend this class and implement the abstract methods to define synchronization behaviour. The
 * resulting subclass can be used directly on non-Android targets, or wrapped by a platform-specific
 * scheduler adapter (e.g. [AndroidFhirSyncWorker] on Android).
 *
 * Call [runSync] from your platform entry-point (background task runner, coroutine, etc.) to
 * execute a sync cycle and receive the terminal [SyncJobStatus].
 */
abstract class FhirSyncWorker {

  private val _syncState = MutableSharedFlow<SyncJobStatus>(extraBufferCapacity = 64)

  /**
   * Live stream of [SyncJobStatus] emitted during [runSync]. All states — including terminal ones —
   * are relayed here so platform schedulers can observe progress without coupling to the internal
   * [FhirSynchronizer].
   */
  internal val syncState: SharedFlow<SyncJobStatus> = _syncState.asSharedFlow()

  /** Returns the [FhirEngine] instance used for interacting with the local FHIR data store. */
  abstract fun getFhirEngine(): FhirEngine

  /** Returns the [DownloadWorkManager] that manages the download process. */
  abstract fun getDownloadWorkManager(): DownloadWorkManager

  /**
   * Returns the [ConflictResolver] that defines how to handle conflicts between local and remote
   * data during synchronisation.
   */
  abstract fun getConflictResolver(): ConflictResolver

  /**
   * Returns the [UploadStrategy] that defines how local changes are uploaded to the server.
   */
  abstract fun getUploadStrategy(): UploadStrategy

  /** Returns the [DataSource] to use; defaults to the one registered with [FhirEngineProvider]. */
  internal open fun getDataSource(): DataSource? = FhirEngineProvider.getDataSource()

  /**
   * Returns the [FhirDataStore] for persisting sync state and metadata.
   *
   * Use [createDataStore] from the platform-specific source set to build an instance backed by the
   * appropriate file path (e.g. the app files directory on Android, the documents directory on iOS).
   */
  internal abstract fun getFhirDataStore(): FhirDataStore

  /**
   * Called during a running sync cycle whenever a non-terminal [SyncJobStatus] is emitted.
   *
   * Override to propagate progress to a platform-specific mechanism (e.g. WorkManager
   * `setProgress` on Android).
   */
  protected open suspend fun onProgress(state: SyncJobStatus) {}

  /**
   * Executes a full sync cycle (download then upload) and returns the terminal [SyncJobStatus].
   *
   * Returns [SyncJobStatus.Failed] immediately if no [DataSource] is available (i.e.
   * [FhirEngineConfiguration.ServerConfiguration] has not been configured).
   *
   * @param uniqueWorkerName Optional key under which the terminal status is persisted in
   *   [getFhirDataStore]. Typically the unique work name assigned by the platform scheduler.
   * @param maxRetries Maximum number of attempts the caller will allow. Used only for logging;
   *   the actual retry decision is made by the platform adapter.
   * @param attempt The current attempt index (0-based), as reported by the platform scheduler.
   */
  suspend fun runSync(
    uniqueWorkerName: String? = null,
    maxRetries: Int = 0,
    attempt: Int = 0,
  ): SyncJobStatus {
    val dataSource =
      getDataSource()
        ?: run {
          Logger.e {
            "FhirEngineConfiguration.ServerConfiguration is not set. " +
              "Call FhirEngineProvider.init to initialise with appropriate configuration."
          }
          return SyncJobStatus.Failed()
        }

    val fhirDataStore = getFhirDataStore()

    val synchronizer =
      FhirSynchronizer(
        getFhirEngine(),
        UploadConfiguration(
          uploader =
            Uploader(
              dataSource = dataSource,
              patchGenerator = PatchGeneratorFactory.byMode(getUploadStrategy().patchGeneratorMode),
              requestGenerator =
                UploadRequestGeneratorFactory.byMode(getUploadStrategy().requestGeneratorMode),
            ),
          uploadStrategy = getUploadStrategy(),
        ),
        DownloadConfiguration(
          DownloaderImpl(dataSource, getDownloadWorkManager()),
          getConflictResolver(),
        ),
        fhirDataStore,
      )

    val job =
      CoroutineScope(Dispatchers.IO).launch {
        synchronizer.syncState.collect { syncJobStatus ->
          _syncState.emit(syncJobStatus)
          when (syncJobStatus) {
            is SyncJobStatus.Succeeded,
            is SyncJobStatus.Failed, -> {
              if (uniqueWorkerName != null) {
                fhirDataStore.writeTerminalSyncJobStatus(uniqueWorkerName, syncJobStatus)
              }
              cancel()
            }
            else -> onProgress(syncJobStatus)
          }
        }
      }

    val result = synchronizer.synchronize()

    runCatching { job.join() }.onFailure { Logger.w(it) { "Failed to join sync job" } }

    Logger.d { "Sync result (attempt ${attempt + 1}/${maxRetries + 1}): $result" }
    return result
  }
}
