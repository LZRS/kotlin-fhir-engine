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

import kotlin.time.Clock

internal actual fun benchmarkPlatformContext(): Any = Unit

/**
 * Fixed, unlike the other platforms.
 *
 * The browser has no directories: the engine applies this as a filename prefix on the OPFS
 * database. Handing out a new prefix per call would not give a cold database anyway, because the
 * first worker keeps its exclusive handle for the lifetime of the page.
 */
internal actual fun benchmarkStorageDirectory(): String? = "benchmark"

internal actual fun platformDescriptor() =
  PlatformDescriptor(target = webTargetName(), os = webUserAgent())

/**
 * False: a browser page cannot close and reopen this database.
 *
 * Closing terminates the SQLite Web Worker, after which every call hangs rather than failing; not
 * closing leaves that worker holding the exclusive OPFS sync access handle, so a second open never
 * completes. The runner degrades [Isolation.FRESH_DATABASE] to [Isolation.CLEAR_TABLES] and records
 * that in the report. A genuinely cold web measurement needs a page reload, which is why the driver
 * app rather than this harness owns web isolation.
 */
internal actual fun supportsFreshDatabase(): Boolean = false

internal actual fun nowIso8601(): String = Clock.System.now().toString()

internal actual suspend fun deleteBenchmarkDatabase(platformContext: Any) = Unit

/**
 * Printed to the console, which Karma forwards to the Gradle output.
 *
 * Writing the file needs a Karma middleware to receive it; that arrives with the web stage.
 */
internal actual suspend fun emitReport(fileName: String, json: String) {
  println("BENCHMARK_REPORT $fileName")
  println(json)
}

internal expect fun webTargetName(): String

internal expect fun webUserAgent(): String
