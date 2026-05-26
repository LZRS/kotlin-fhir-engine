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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTask
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate

/**
 * Integrates FHIR sync with iOS [BGTaskScheduler] for true background execution.
 *
 * Unlike [IosSyncScheduler] (which is tied to a foreground coroutine collecting a [Flow]), this
 * manager lets the OS wake the app while it is suspended and run a full sync cycle.
 *
 * ### Required Info.plist keys
 * ```xml
 * <key>BGTaskSchedulerPermittedIdentifiers</key>
 * <array><string>com.example.app.fhirsync</string></array>
 * <key>UIBackgroundModes</key>
 * <array><string>fetch</string></array>
 * ```
 *
 * ### Usage
 * ```kotlin
 * // In your SwiftUI App.init() — must run before the first scene is created
 * IosBackgroundSyncManager.register(
 *     taskIdentifier = "com.example.app.fhirsync",
 *     downloadWorkManager = ...,
 *     conflictResolver = ...,
 *     uploadStrategy = ...,
 *     setup = { runCatching { FhirEngineProvider.init(...) } },
 * )
 *
 * // Then once to submit the first request to the OS
 * IosBackgroundSyncManager.scheduleNext("com.example.app.fhirsync")
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
object IosBackgroundSyncManager {

  private var downloadWorkManager: DownloadWorkManager? = null
  private var conflictResolver: ConflictResolver? = null
  private var uploadStrategy: UploadStrategy? = null
  private var minIntervalSeconds: Double = 15.0 * 60.0
  private var setup: (() -> Unit)? = null

  /**
   * Registers the background sync handler with [BGTaskScheduler].
   *
   * **Must be called before the app finishes launching** — i.e. inside `App.init()` for a SwiftUI
   * `@main` struct, or `application(_:didFinishLaunchingWithOptions:)` for UIKit. BGTaskScheduler
   * rejects registrations that arrive after launch completes.
   *
   * @param taskIdentifier Must match an entry in `BGTaskSchedulerPermittedIdentifiers` in Info.plist.
   * @param downloadWorkManager App-specific download manager.
   * @param conflictResolver Conflict resolution strategy.
   * @param uploadStrategy Upload batching and method configuration.
   * @param minIntervalSeconds The earliest the OS will honour a new wake after scheduling. The OS
   *   may wait longer depending on battery, network, and usage patterns.
   * @param setup Called at the start of each background wake to ensure [FhirEngineProvider] is
   *   ready. Wrap with `runCatching` so a "already initialized" throw is swallowed silently.
   */
  fun register(
    taskIdentifier: String,
    downloadWorkManager: DownloadWorkManager,
    conflictResolver: ConflictResolver,
    uploadStrategy: UploadStrategy,
    minIntervalSeconds: Double = 15.0 * 60.0,
    setup: () -> Unit,
  ) {
    this.downloadWorkManager = downloadWorkManager
    this.conflictResolver = conflictResolver
    this.uploadStrategy = uploadStrategy
    this.minIntervalSeconds = minIntervalSeconds
    this.setup = setup

    BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
      identifier = taskIdentifier,
      usingQueue = null,
    ) { bgTask ->
      val task = bgTask as? BGAppRefreshTask ?: return@registerForTaskWithIdentifier
      handleBackgroundTask(task, taskIdentifier)
    }
  }

  /**
   * Submits a [BGAppRefreshTaskRequest] to the OS scheduler.
   *
   * Call once after [register] to schedule the first run. Each subsequent run is auto-rescheduled
   * inside [handleBackgroundTask] before the sync starts.
   */
  fun scheduleNext(taskIdentifier: String) {
    val request = BGAppRefreshTaskRequest(taskIdentifier)
    request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(minIntervalSeconds)
    BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
  }

  /** Cancels any pending background sync request with the given [taskIdentifier]. */
  fun cancel(taskIdentifier: String) {
    BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(taskIdentifier)
  }

  private fun handleBackgroundTask(task: BGAppRefreshTask, taskIdentifier: String) {
    // Reschedule before doing anything — if we're killed mid-sync the next cycle is still queued.
    scheduleNext(taskIdentifier)

    // Re-initialize dependencies on each wake (each background launch is a fresh process state).
    runCatching { setup?.invoke() }

    val fhirEngine = runCatching { FhirEngineProvider.getInstance() }.getOrNull()
    val dataSource = FhirEngineProvider.getDataSource()
    val dlManager = downloadWorkManager
    val resolver = conflictResolver
    val strategy = uploadStrategy

    if (fhirEngine == null || dataSource == null || dlManager == null ||
      resolver == null || strategy == null) {
      task.setTaskCompletedWithSuccess(false)
      return
    }

    val syncCore = FhirSyncCore(
      fhirEngine = fhirEngine,
      dataSource = dataSource,
      downloadWorkManager = dlManager,
      conflictResolver = resolver,
      uploadStrategy = strategy,
      fhirDataStore = FhirDataStore(createDataStore()),
    )

    var job: Job? = null
    job = CoroutineScope(Dispatchers.IO).launch {
      try {
        val result = syncCore.execute(workerName = taskIdentifier, onProgress = {})
        task.setTaskCompletedWithSuccess(result is SyncJobStatus.Succeeded)
      } catch (_: CancellationException) {
        task.setTaskCompletedWithSuccess(false)
      } catch (_: Exception) {
        task.setTaskCompletedWithSuccess(false)
      }
    }

    // OS calls this when it needs the task to stop (battery / time limit).
    task.expirationHandler = {
      job?.cancel()
      task.setTaskCompletedWithSuccess(false)
    }
  }
}
