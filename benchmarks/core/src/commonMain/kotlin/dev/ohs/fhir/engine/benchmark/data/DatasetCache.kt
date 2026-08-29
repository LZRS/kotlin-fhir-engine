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
package dev.ohs.fhir.engine.benchmark.data

import kotlinx.serialization.Serializable

/**
 * Somewhere small and fast to keep what a corpus scan learned.
 *
 * Injected rather than called directly so this package stays free of platform code, the same way
 * the line reader is.
 */
interface DatasetCache {
  suspend fun read(key: String): String?

  suspend fun write(key: String, contents: String)
}

/**
 * Everything [NdjsonDataset] knows about a corpus that is not the corpus itself.
 *
 * Recomputing this means parsing every line: about four minutes for a 50,000-patient corpus on the
 * benchmark tablet, repeated for every macrobenchmark iteration because each one is a new process.
 */
@Serializable
internal data class DatasetMetadata(
  val counts: Map<String, Int>,
  val patientIds: List<String>,
  val sampleObservationCode: String,
  val sampleOrganizationId: String,
  val fingerprint: String,
)
