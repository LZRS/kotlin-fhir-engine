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

import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.Meeseeks

internal expect fun appContextFromPlatform(platformContext: Any): AppContext

/**
 * Creates a [SyncScheduler] backed by [MeeseeksSyncScheduler].
 *
 * Initializes the Meeseeks runtime with a [SyncWorker] for the given [fhirSyncWorker] and returns
 * a scheduler whose [SyncScheduler.runOneTimeSync] dispatches work through the platform background
 * task system.
 *
 * @param platformContext The platform-specific context (e.g. [android.content.Context] on Android).
 * @param fhirSyncWorker The [FhirSyncWorker] that performs the actual sync.
 */
fun createMeeseeksSyncScheduler(
  platformContext: Any,
  fhirSyncWorker: FhirSyncWorker,
): SyncScheduler {
  val appContext = appContextFromPlatform(platformContext)
  val bgTaskManager =
    Meeseeks.initialize(appContext) { register { ctx -> SyncWorker(ctx, fhirSyncWorker) } }
  return MeeseeksSyncScheduler(bgTaskManager, fhirSyncWorker)
}
