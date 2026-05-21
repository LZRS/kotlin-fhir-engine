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

import dev.mattramotar.meeseeks.runtime.BGTaskManager
import dev.mattramotar.meeseeks.runtime.TaskHandle
import dev.mattramotar.meeseeks.runtime.TaskStatus
import dev.mattramotar.meeseeks.runtime.oneTime
import dev.mattramotar.meeseeks.runtime.periodic
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * [SyncScheduler] implementation that uses Meeseeks to schedule one-time and periodic sync jobs.
 *
 * The [bgTaskManager] must be initialized via [dev.mattramotar.meeseeks.runtime.Meeseeks.initialize]
 * with [SyncWorker] registered before constructing this scheduler:
 * ```
 * val fhirSyncWorker: FhirSyncWorker = MyFhirSyncWorker()
 * val bgTaskManager = Meeseeks.initialize(appContext) {
 *     register { ctx -> SyncWorker(ctx, fhirSyncWorker) }
 * }
 * val scheduler = MeeseeksSyncScheduler(bgTaskManager, fhirSyncWorker)
 * ```
 */
internal class MeeseeksSyncScheduler(
    private val bgTaskManager: BGTaskManager,
    private val fhirSyncWorker: FhirSyncWorker,
) : SyncScheduler {

    private var oneTimeTaskHandle: TaskHandle? = null
    private var periodicTaskHandle: TaskHandle? = null

    override suspend fun runOneTimeSync(
        retryConfiguration: RetryConfiguration?,
    ): Flow<CurrentSyncJobStatus> {
        val handle =
            bgTaskManager.oneTime(SyncPayload) {
                retryConfiguration?.let { config ->
                    retryWithExponentialBackoff(
                        initialDelay = config.backoffCriteria.backoffDelay,
                        maxAttempts = config.maxRetries,
                    )
                }
            }
        oneTimeTaskHandle = handle
        return toCurrentSyncJobStatusFlow(handle)
    }

    override suspend fun schedulePeriodicSync(
        config: PeriodicSyncConfiguration,
    ): Flow<PeriodicSyncJobStatus> {
        val handle =
            bgTaskManager.periodic(payload = SyncPayload, every = config.repeat.interval) {
                config.retryConfiguration?.let { retryConfig ->
                    retryWithExponentialBackoff(
                        initialDelay = retryConfig.backoffCriteria.backoffDelay,
                        maxAttempts = retryConfig.maxRetries,
                    )
                }
                with(config.syncConstraints) {
                    if (requiredNetworkType != NetworkType.NOT_REQUIRED) requireNetwork()
                    if (requiresBatteryNotLow) requireBatteryNotLow()
                    if (requiresCharging) requireCharging()
                }
            }
        periodicTaskHandle = handle
        return toPeriodicSyncJobStatusFlow(handle)
    }

    override suspend fun cancelOneTimeSync() {
        oneTimeTaskHandle?.cancel()
        oneTimeTaskHandle = null
    }

    override suspend fun cancelPeriodicSync() {
        periodicTaskHandle?.cancel()
        periodicTaskHandle = null
    }

    private fun toCurrentSyncJobStatusFlow(handle: TaskHandle): Flow<CurrentSyncJobStatus> =
        channelFlow {
            val syncStateJob = launch {
                fhirSyncWorker.syncState.collect { syncJobStatus ->
                    send(CurrentSyncJobStatus.Running(syncJobStatus))
                }
            }

            handle.observe().collect { taskStatus ->
                when (taskStatus) {
                    TaskStatus.Pending -> send(CurrentSyncJobStatus.Enqueued)
                    TaskStatus.Running -> Unit
                    TaskStatus.Finished.Completed -> {
                        syncStateJob.cancel()
                        send(CurrentSyncJobStatus.Succeeded(Clock.System.now()))
                    }
                    TaskStatus.Finished.Failed -> {
                        syncStateJob.cancel()
                        send(CurrentSyncJobStatus.Failed(Clock.System.now()))
                    }
                    TaskStatus.Finished.Cancelled -> {
                        syncStateJob.cancel()
                        send(CurrentSyncJobStatus.Cancelled)
                    }
                }
            }

            syncStateJob.cancel()
        }

    private fun toPeriodicSyncJobStatusFlow(handle: TaskHandle): Flow<PeriodicSyncJobStatus> =
        channelFlow {
            var lastSyncJobStatus: LastSyncJobStatus? = null

            val syncStateJob = launch {
                fhirSyncWorker.syncState.collect { syncJobStatus ->
                    send(PeriodicSyncJobStatus(lastSyncJobStatus, CurrentSyncJobStatus.Running(syncJobStatus)))
                    when (syncJobStatus) {
                        is SyncJobStatus.Succeeded ->
                            lastSyncJobStatus = LastSyncJobStatus.Succeeded(Clock.System.now())
                        is SyncJobStatus.Failed ->
                            lastSyncJobStatus = LastSyncJobStatus.Failed(Clock.System.now())
                        else -> Unit
                    }
                }
            }

            handle.observe().collect { taskStatus ->
                when (taskStatus) {
                    TaskStatus.Pending ->
                        send(PeriodicSyncJobStatus(lastSyncJobStatus, CurrentSyncJobStatus.Enqueued))
                    TaskStatus.Finished.Cancelled -> {
                        syncStateJob.cancel()
                        send(PeriodicSyncJobStatus(lastSyncJobStatus, CurrentSyncJobStatus.Cancelled))
                    }
                    else -> Unit
                }
            }

            syncStateJob.cancel()
        }
}
