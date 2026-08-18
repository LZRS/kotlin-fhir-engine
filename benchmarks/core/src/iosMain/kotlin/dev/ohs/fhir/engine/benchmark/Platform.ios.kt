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
import platform.Foundation.NSTemporaryDirectory
import platform.UIKit.UIDevice

internal actual fun benchmarkPlatformContext(): Any = Unit

/** A fresh directory per call, so reopening the engine gives a genuinely cold database. */
internal actual fun benchmarkStorageDirectory(): String? =
  "${NSTemporaryDirectory()}fhir-benchmark-${databaseCounter++}"

private var databaseCounter = 0

internal actual fun platformDescriptor() =
  PlatformDescriptor(
    target = "ios",
    os = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}",
    deviceModel = UIDevice.currentDevice.model,
  )

internal actual fun supportsFreshDatabase(): Boolean = true

internal actual fun nowIso8601(): String = Clock.System.now().toString()

internal actual suspend fun deleteBenchmarkDatabase(platformContext: Any) = Unit

/**
 * Printed rather than written to disk. Reading a file back out of the simulator sandbox needs
 * plumbing this module does not have yet; the iOS harness is the last stage for that reason.
 */
internal actual suspend fun emitReport(fileName: String, json: String) {
  println("BENCHMARK_REPORT $fileName")
  println(json)
}
