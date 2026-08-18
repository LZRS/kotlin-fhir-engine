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

/**
 * Marks [block] as the measured region under the name [name].
 *
 * The span name is the workload id, so the same identifier keys the Android Perfetto trace, the
 * browser performance timeline and the JSON report. On Android this emits an `androidx.tracing`
 * section that macrobenchmark's `TraceSectionMetric` reads; elsewhere it is inert, because the
 * in-process runner times the block itself.
 */
internal expect suspend fun <T> benchmarkSpan(name: String, block: suspend () -> T): T
