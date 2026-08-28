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

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest

private fun patient(id: String) = """{"resourceType":"Patient","id":"$id"}"""

private fun organization(id: String) = """{"resourceType":"Organization","id":"$id"}"""

private fun observation(id: String, code: String) =
  """{"resourceType":"Observation","id":"$id","status":"final",""" +
    """"code":{"coding":[{"code":"$code"}]}}"""

/** A stand-in for the platform reader: one flow per file, nothing held whole. */
private class FakeLines(private val files: Map<String, List<String>>) {
  val requested = mutableListOf<String>()

  operator fun invoke(name: String): Flow<String> {
    requested += name
    return files[name].orEmpty().asFlow()
  }
}

class NdjsonDatasetTest {

  @Test
  fun `orders referenced types before the resources that reference them`() = runTest {
    val lines =
      FakeLines(
        mapOf(
          "Patient.ndjson" to listOf(patient("p1")),
          "Organization.ndjson" to listOf(organization("o1")),
        ),
      )

    val dataset =
      NdjsonDataset.load(
        fileNames = listOf("Patient.ndjson", "Organization.ndjson"),
        seed = 1,
        requestedPopulation = 1,
        lines = lines::invoke,
      )

    assertEquals(listOf("o1", "p1"), dataset.allResources.map { it.id })
    assertEquals(listOf("p1"), dataset.patientIds)
    assertEquals(1, dataset.population)
  }

  @Test
  fun `keeps the valid lines when one line fails to parse`() = runTest {
    val lines =
      FakeLines(
        mapOf("Patient.ndjson" to listOf(patient("p1"), "{not json", "", patient("p2"))),
      )

    val dataset =
      NdjsonDataset.load(
        fileNames = listOf("Patient.ndjson"),
        seed = 1,
        requestedPopulation = 2,
        lines = lines::invoke,
      )

    assertEquals(listOf("p1", "p2"), dataset.patientIds)
    assertEquals(1, dataset.parseFailures.size)
    assertContains(dataset.parseFailures.single(), "Patient.ndjson")
  }

  @Test
  fun `discovers the most common observation code`() = runTest {
    val lines =
      FakeLines(
        mapOf(
          "Patient.ndjson" to listOf(patient("p1")),
          "Observation.ndjson" to
            listOf(
              observation("o1", "1234-5"),
              observation("o2", "1234-5"),
              observation("o3", "9"),
            ),
        ),
      )

    val dataset =
      NdjsonDataset.load(
        fileNames = listOf("Patient.ndjson", "Observation.ndjson"),
        seed = 1,
        requestedPopulation = 1,
        lines = lines::invoke,
      )

    assertEquals("1234-5", dataset.sampleObservationCode)
  }

  @Test
  fun `never opens a file whose type no workload queries`() = runTest {
    val lines = FakeLines(mapOf("Patient.ndjson" to listOf(patient("p1"))))

    NdjsonDataset.load(
      fileNames = listOf("Patient.ndjson", "Claim.ndjson"),
      seed = 1,
      requestedPopulation = 1,
      lines = lines::invoke,
    )

    assertEquals(listOf("Patient.ndjson"), lines.requested)
  }

  @Test
  fun `is empty when no files are named`() = runTest {
    val lines = FakeLines(emptyMap())

    val dataset =
      NdjsonDataset.load(
        fileNames = emptyList(),
        seed = 1,
        requestedPopulation = 0,
        lines = lines::invoke,
      )

    assertEquals(0, dataset.population)
    assertTrue(dataset.patientIds.isEmpty())
    assertTrue(dataset.allResources.isEmpty())
  }
}
