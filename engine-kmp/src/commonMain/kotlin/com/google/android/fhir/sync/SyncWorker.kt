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
import dev.mattramotar.meeseeks.runtime.RuntimeContext
import dev.mattramotar.meeseeks.runtime.TaskPayload
import dev.mattramotar.meeseeks.runtime.TaskResult
import dev.mattramotar.meeseeks.runtime.Worker
import kotlinx.serialization.Serializable

@Serializable
internal object SyncPayload : TaskPayload

/**
 * Meeseeks [Worker] adapter for [FhirSyncWorker].
 *
 * Bridges the Meeseeks background-task runtime with the platform-agnostic [FhirSyncWorker] by
 * delegating execution to [FhirSyncWorker.runSync] and mapping the terminal [SyncJobStatus] to
 * a [TaskResult].
 */
internal class SyncWorker(
  appContext: AppContext,
  private val fhirSyncWorker: FhirSyncWorker,
) : Worker<SyncPayload>(appContext) {

  override suspend fun run(payload: SyncPayload, context: RuntimeContext): TaskResult {
    return try {
      when (fhirSyncWorker.runSync()) {
        is SyncJobStatus.Succeeded -> TaskResult.Success
        is SyncJobStatus.Failed -> TaskResult.Retry
        else -> TaskResult.Failure.Permanent()
      }
    } catch (e: Exception) {
      TaskResult.Retry
    }
  }
}
