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

/**
 * Provides the components required for a FHIR sync cycle.
 *
 * Implement this interface once with your app's dependencies. A platform-specific adapter
 * ([FhirSyncJobWorker] on Android, [IosSyncJobScheduler] on iOS, [DesktopSyncJobScheduler] on
 * desktop) calls these methods when scheduling or executing a sync.
 *
 * Example:
 * ```kotlin
 * class MyFhirSyncJob : FhirSyncJob {
 *   override fun getFhirEngine() = FhirEngineProvider.getInstance(context)
 *   override fun getDownloadWorkManager() = MyDownloadWorkManager()
 *   override fun getConflictResolver() = AcceptRemoteConflictResolver
 *   override fun getUploadStrategy() = UploadStrategy.forBundleRequest(...)
 * }
 * ```
 */
interface FhirSyncJob {
  fun getFhirEngine(): FhirEngine

  fun getDownloadWorkManager(): DownloadWorkManager

  fun getConflictResolver(): ConflictResolver

  fun getUploadStrategy(): UploadStrategy
}
