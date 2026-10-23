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
package dev.ohs.fhir.engine.search

import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.String as FhirString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Executes sorted searches against a real database, rather than asserting the SQL they compile to.
 *
 * A resource can hold several values for the sort parameter. It sorts by its smallest when
 * ascending and its largest when descending, and one with no value sorts last either way. `a` has
 * its smallest name last and `b` has it first, so a sort that reads whichever row comes to hand
 * misorders at least one of them. `d` has neither a name nor a birth date.
 */
class SortMatchingTest {

  @BeforeTest
  fun setUp() = runTest {
    FhirEngineProvider.init(
      FhirEngineConfiguration(testMode = true, storageDirectory = testStorageDirectory()),
    )
    FhirEngineProvider.getInstance()
      .create(
        patient("a", "1990-01-01", "Zed", "Abe"),
        patient("b", "2000-01-01", "Bob", "Yan"),
        patient("c", "2010-01-01", "Dan"),
        patient("d", birthDate = null),
      )
  }

  @AfterTest
  fun tearDown() {
    FhirEngineProvider.resetForTesting()
  }

  @Test
  fun `ascending sort orders each patient by its smallest name`() = runTest {
    assertEquals(listOf("a", "b", "c", "d"), sortByGiven(Order.ASCENDING))
  }

  @Test
  fun `descending sort orders each patient by its largest name`() = runTest {
    assertEquals(listOf("a", "b", "c", "d"), sortByGiven(Order.DESCENDING))
  }

  @Test
  fun `ascending date sort puts a patient with no birth date last`() = runTest {
    assertEquals(listOf("a", "b", "c", "d"), sortByBirthDate(Order.ASCENDING))
  }

  @Test
  fun `descending date sort puts a patient with no birth date last`() = runTest {
    assertEquals(listOf("c", "b", "a", "d"), sortByBirthDate(Order.DESCENDING))
  }

  private suspend fun sortByGiven(order: Order): List<String> =
    FhirEngineProvider.getInstance()
      .search<Patient> { sort(StringClientParam("given"), order) }
      .mapNotNull { it.resource.id }

  private suspend fun sortByBirthDate(order: Order): List<String> =
    FhirEngineProvider.getInstance()
      .search<Patient> { sort(DateClientParam("birthdate"), order) }
      .mapNotNull { it.resource.id }

  private fun patient(id: String, birthDate: String?, vararg given: String) =
    Patient(
      id = id,
      birthDate = birthDate?.let { Date(value = FhirDate.fromString(it)) },
      name =
        if (given.isEmpty()) {
          emptyList()
        } else {
          listOf(HumanName(given = given.map { FhirString(value = it) }))
        },
    )
}
