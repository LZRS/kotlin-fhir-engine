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

package com.example.enginekmpapp

import com.google.android.fhir.sync.AcceptLocalConflictResolver
import com.google.android.fhir.sync.IosBackgroundSyncManager
import com.google.android.fhir.sync.upload.HttpCreateMethod
import com.google.android.fhir.sync.upload.HttpUpdateMethod
import com.google.android.fhir.sync.upload.UploadStrategy

const val DEMO_BG_SYNC_TASK_ID = "com.example.enginekmpapp.backgroundsync"

/**
 * Registers the demo app's FHIR background sync with [IosBackgroundSyncManager].
 *
 * **Call once inside `iOSApp.init()` — before the first SwiftUI scene is created.**
 * BGTaskScheduler rejects registrations that arrive after the app finishes launching.
 *
 * The [setup] lambda re-initializes [com.google.android.fhir.FhirEngineProvider] on each
 * background wake. `runCatching` makes it a no-op when the provider is already initialized
 * (foreground launch).
 */
fun setupBackgroundSync(taskIdentifier: String = DEMO_BG_SYNC_TASK_ID) {
  IosBackgroundSyncManager.register(
    taskIdentifier = taskIdentifier,
    downloadWorkManager = TimestampBasedDownloadWorkManagerImpl(sharedDemoDataStore),
    conflictResolver = AcceptLocalConflictResolver,
    uploadStrategy =
      UploadStrategy.forBundleRequest(
        methodForCreate = HttpCreateMethod.PUT,
        methodForUpdate = HttpUpdateMethod.PATCH,
        squash = true,
        bundleSize = 500,
      ),
    setup = { runCatching { initFhirEngine() } },
  )
}

/**
 * Submits the first (or next) background sync request to the OS.
 *
 * Call once after [setupBackgroundSync]. Subsequent runs are auto-rescheduled by the manager
 * at the start of each background wake.
 */
fun scheduleBackgroundSync(taskIdentifier: String = DEMO_BG_SYNC_TASK_ID) {
  IosBackgroundSyncManager.scheduleNext(taskIdentifier)
}

/** Cancels any pending background sync request. */
fun cancelBackgroundSync(taskIdentifier: String = DEMO_BG_SYNC_TASK_ID) {
  IosBackgroundSyncManager.cancel(taskIdentifier)
}
