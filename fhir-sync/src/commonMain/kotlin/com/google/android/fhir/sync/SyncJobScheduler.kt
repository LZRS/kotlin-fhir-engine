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

import kotlinx.coroutines.flow.Flow

/**
 * Schedules and observes FHIR sync jobs.
 *
 * Obtain a platform-specific implementation:
 * - **Android**: [AndroidSyncJobScheduler] (backed by WorkManager)
 * - **iOS**: [IosSyncJobScheduler] (backed by BGTaskScheduler)
 * - **Desktop**: [DesktopSyncJobScheduler] (backed by coroutines)
 *
 * The [Flow] returned by each scheduling function emits status updates for the lifetime of the
 * job. Collect it in a coroutine tied to your UI or application lifecycle.
 */
interface SyncJobScheduler {

  /**
   * Runs a one-time sync immediately and returns a [Flow] of [CurrentSyncJobStatus] updates.
   *
   * If a one-time sync is already running, the platform adapter may coalesce requests (e.g.
   * WorkManager's [ExistingWorkPolicy.KEEP]).
   */
  suspend fun runOneTimeSync(
    retryConfiguration: RetryConfiguration? = null,
  ): Flow<CurrentSyncJobStatus>

  /**
   * Schedules a recurring sync at the interval defined by [config] and returns a [Flow] of
   * [PeriodicSyncJobStatus] updates containing both the current and last-completed status.
   */
  suspend fun schedulePeriodicSync(
    config: PeriodicSyncConfiguration,
  ): Flow<PeriodicSyncJobStatus>

  /** Cancels any pending or running one-time sync. */
  suspend fun cancelOneTimeSync()

  /** Cancels the periodic sync schedule. */
  suspend fun cancelPeriodicSync()
}
