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

import android.os.Trace

/**
 * Emits an `atrace` section that macrobenchmark's `TraceSectionMetric` reads back out of the
 * Perfetto trace. The section name is the workload id, which is also the key in the JSON report.
 *
 * Section names are truncated by the platform at 127 characters; workload ids are far shorter.
 */
internal actual suspend fun <T> benchmarkSpan(name: String, block: suspend () -> T): T {
  Trace.beginSection(name)
  try {
    return block()
  } finally {
    Trace.endSection()
  }
}
