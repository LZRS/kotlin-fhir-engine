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

import kotlinx.coroutines.flow.Flow

/** Platform context for [dev.ohs.fhir.engine.FhirEngineProvider.init]; `Unit` off Android. */
internal expect fun benchmarkPlatformContext(): Any

/** Storage directory for the benchmark database, or null where the platform ignores it. */
internal expect fun benchmarkStorageDirectory(): String?

internal expect fun platformDescriptor(): PlatformDescriptor

/**
 * False on web: closing wedges the SQLite Web Worker, and not closing leaves it holding the
 * exclusive OPFS handle. The runner degrades [Isolation.FRESH_DATABASE] and says so in the report.
 */
internal expect fun supportsFreshDatabase(): Boolean

/** Files under the packaged benchmark data directory, or empty where none is available. */
internal expect suspend fun listDataFiles(): List<String>

/**
 * One line per NDJSON record, empty where the file is absent.
 *
 * A line at a time rather than the whole file: at benchmark populations a single Synthea file runs
 * to hundreds of megabytes, and holding it as a string costs more than the resources parsed out of
 * it. The flow is cold, so nothing is read until it is collected.
 */
internal expect fun dataFileLines(relativePath: String): Flow<String>

/** ISO-8601 timestamp for the report. */
internal expect fun nowIso8601(): String

/** No-op where [benchmarkStorageDirectory] already hands out a fresh location per call. */
internal expect suspend fun deleteBenchmarkDatabase(platformContext: Any)

/** Writes [json] wherever this platform can be read from afterwards. */
internal expect suspend fun emitReport(fileName: String, json: String)
