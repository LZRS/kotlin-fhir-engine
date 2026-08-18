/*
 * Copyright 2026 Open Health Stack Foundation
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
package dev.ohs.fhir.engine.benchmark

/** Platform context for [dev.ohs.fhir.engine.FhirEngineProvider.init]; `Unit` off Android. */
internal expect fun benchmarkPlatformContext(): Any

/** Storage directory for the benchmark database, or null where the platform ignores it. */
internal expect fun benchmarkStorageDirectory(): String?

internal expect fun platformDescriptor(): PlatformDescriptor

/**
 * Whether this platform can close and reopen the engine's database inside one process.
 *
 * False on web: closing wedges the SQLite Web Worker and skipping the close leaves it holding the
 * exclusive OPFS handle, so the reopen never completes. Web therefore cannot honour
 * [Isolation.FRESH_DATABASE] in-process; the runner degrades it to [Isolation.CLEAR_TABLES] and
 * records that in the report rather than reporting a cold number that was actually warm.
 */
internal expect fun supportsFreshDatabase(): Boolean

/** ISO-8601 timestamp for the report. */
internal expect fun nowIso8601(): String

/**
 * Deletes the benchmark database file, where the platform needs an explicit delete to get a cold
 * one. A no-op where [benchmarkStorageDirectory] already hands out a fresh location per call.
 */
internal expect suspend fun deleteBenchmarkDatabase(platformContext: Any)

/** Writes [json] wherever this platform can be read from afterwards. */
internal expect suspend fun emitReport(fileName: String, json: String)
