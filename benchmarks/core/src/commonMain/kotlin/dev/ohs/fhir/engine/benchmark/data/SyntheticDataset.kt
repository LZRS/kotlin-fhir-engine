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

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.engine.benchmark.DatasetManifest
import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.Decimal as FhirDecimal
import dev.ohs.fhir.model.r4.Encounter
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Practitioner
import dev.ohs.fhir.model.r4.Quantity
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.datetime.LocalDate

/**
 * Deterministic fallback dataset, so the harness runs on a fresh clone with no Java tooling.
 * Synthea remains the dataset for quotable numbers; reports record which kind ran so the two are
 * never compared.
 *
 * Per patient: one Encounter, three Observations, one Condition, plus shared Organizations and
 * Practitioners, so include, revInclude and has queries have something to traverse.
 */
class SyntheticDataset(
  override val population: Int,
  private val seed: Int,
) : Dataset {

  private val random = Random(seed)

  private val organizations: List<Organization>
  private val practitioners: List<Practitioner>
  private val patients: List<Patient>
  private val encounters: List<Encounter>
  private val observations: List<Observation>
  private val conditions: List<Condition>

  init {
    organizations =
      (0 until maxOf(1, population / 20)).map { index ->
        Organization(
          id = organizationId(index),
          name = FhirString(value = "Benchmark Organization $index"),
          active = FhirBoolean(value = true),
        )
      }
    practitioners =
      (0 until maxOf(1, population / 10)).map { index ->
        Practitioner(
          id = "bench-practitioner-$index",
          active = FhirBoolean(value = true),
          name =
            listOf(
              HumanName(
                family = FhirString(value = composedName(index)),
                given = listOf(FhirString(value = composedName(index + NAME_COMBINATIONS / 2))),
              ),
            ),
        )
      }
    patients = (0 until population).map { buildPatient(it) }
    encounters = patients.mapIndexed { index, patient -> buildEncounter(index, patient) }
    observations =
      patients.flatMapIndexed { index, patient ->
        (0 until OBSERVATIONS_PER_PATIENT).map { slot -> buildObservation(index, slot, patient) }
      }
    conditions = patients.mapIndexed { index, patient -> buildCondition(index, patient) }
  }

  // Referenced resources first: organizations and practitioners before the patients pointing at
  // them, patients before the clinical resources pointing at patients.
  private val all: List<Resource> =
    organizations + practitioners + patients + encounters + observations + conditions

  override val resourceCount: Int = all.size

  /** Generated in memory and small by construction, so the flow just replays the list. */
  override fun resources(): Flow<Resource> = all.asFlow()

  override val patientIds: List<String> = patients.map { it.id!! }

  override val sampleObservationCode: String = OBSERVATION_CODES.first()

  override val sampleOrganizationId: String = organizationId(0)

  // Patient 0's own names, so a search for either matches roughly population/676 patients.
  override val sampleFamilyName: String = composedName(0)

  override val sampleGivenName: String = composedName(NAME_COMBINATIONS / 2)

  override fun manifest() =
    DatasetManifest(
      kind = "synthetic",
      population = population,
      seed = seed,
      resourceCounts =
        mapOf(
          "Organization" to organizations.size,
          "Practitioner" to practitioners.size,
          "Patient" to patients.size,
          "Encounter" to encounters.size,
          "Observation" to observations.size,
          "Condition" to conditions.size,
        ),
      fingerprint = fingerprint(),
    )

  /** Reports with differing fingerprints did not measure the same thing and cannot be compared. */
  private fun fingerprint(): String {
    var hash = 17L
    for (resource in all) {
      hash = hash * 31 + resource.id.hashCode()
      hash = hash * 31 + resource::class.simpleName.hashCode()
    }
    hash = hash * 31 + population
    hash = hash * 31 + seed
    return hash.toULong().toString(16).padStart(16, '0')
  }

  private fun buildPatient(index: Int): Patient =
    Patient(
      id = "bench-patient-$index",
      active = FhirBoolean(value = index % 4 != 0),
      name =
        listOf(
          HumanName(
            family = FhirString(value = composedName(index)),
            // Offset so a patient's given and family names differ, and so a search for one cannot
            // accidentally match the other.
            given = listOf(FhirString(value = composedName(index + NAME_COMBINATIONS / 2))),
          ),
        ),
      gender =
        Enumeration(
          value = if (index % 2 == 0) AdministrativeGender.Male else AdministrativeGender.Female,
        ),
      birthDate =
        Date(
          value =
            FhirDate.Date(
              date =
                LocalDate(
                  year = 1940 + random.nextInt(70),
                  monthNumber = 1 + random.nextInt(12),
                  dayOfMonth = 1 + random.nextInt(28),
                ),
            ),
        ),
      managingOrganization =
        Reference(
          reference =
            FhirString(value = "Organization/${organizationId(index % organizations.size)}"),
        ),
      generalPractitioner =
        listOf(
          Reference(
            reference =
              FhirString(value = "Practitioner/bench-practitioner-${index % practitioners.size}"),
          ),
        ),
    )

  private fun buildEncounter(index: Int, patient: Patient): Encounter =
    Encounter(
      id = "bench-encounter-$index",
      status = Enumeration(value = Encounter.EncounterStatus.Finished),
      `class` = Coding(system = Uri(value = ENCOUNTER_CLASS_SYSTEM), code = Code(value = "AMB")),
      subject = Reference(reference = FhirString(value = "Patient/${patient.id}")),
    )

  private fun buildObservation(index: Int, slot: Int, patient: Patient): Observation {
    val code = OBSERVATION_CODES[(index + slot) % OBSERVATION_CODES.size]
    return Observation(
      id = "bench-observation-$index-$slot",
      status = Enumeration(value = Observation.ObservationStatus.Final),
      code =
        CodeableConcept(
          coding = listOf(Coding(system = Uri(value = LOINC_SYSTEM), code = Code(value = code))),
        ),
      subject = Reference(reference = FhirString(value = "Patient/${patient.id}")),
      value =
        Observation.Value.Quantity(
          Quantity(
            value = FhirDecimal(value = BigDecimal.fromInt(40 + random.nextInt(120))),
            unit = FhirString(value = "mg"),
            system = Uri(value = UCUM_SYSTEM),
            code = Code(value = "mg"),
          ),
        ),
    )
  }

  private fun buildCondition(index: Int, patient: Patient): Condition =
    Condition(
      id = "bench-condition-$index",
      code =
        CodeableConcept(
          coding =
            listOf(
              Coding(
                system = Uri(value = SNOMED_SYSTEM),
                code = Code(value = CONDITION_CODES[index % CONDITION_CODES.size]),
              ),
            ),
        ),
      subject = Reference(reference = FhirString(value = "Patient/${patient.id}")),
    )

  private fun organizationId(index: Int) = "bench-organization-$index"

  companion object {
    const val OBSERVATIONS_PER_PATIENT = 3

    const val LOINC_SYSTEM = "http://loinc.org"
    const val SNOMED_SYSTEM = "http://snomed.info/sct"
    const val UCUM_SYSTEM = "http://unitsofmeasure.org"
    const val ENCOUNTER_CLASS_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ActCode"

    /** Real LOINC codes so the shape of the token index matches production data. */
    val OBSERVATION_CODES = listOf("8867-4", "8480-6", "8462-4", "29463-7", "39156-5")

    val CONDITION_CODES = listOf("44054006", "195967001", "59621000", "271737000")

    /**
     * Names are composed rather than listed, because the count matters more than the realism.
     *
     * With a short list every prefix search matches a large share of the corpus — eight surnames
     * meant `family = "Smith"` matched one patient in eight — and at that selectivity no index can
     * help, so the search benchmarks could not tell a good index from a missing one. Composing two
     * syllable tables gives 676 distinct surnames and 676 given names, so searching one whole name
     * matches roughly one patient in 676, which is the order a real name search sees.
     */
    private val STEMS =
      listOf(
        "Ab",
        "Bo",
        "Ch",
        "Da",
        "Ek",
        "Fa",
        "Gu",
        "Ha",
        "Ib",
        "Ji",
        "Ka",
        "Lo",
        "Mu",
        "Na",
        "Ob",
        "Pa",
        "Qu",
        "Ra",
        "Si",
        "Ta",
        "Ug",
        "Ve",
        "Wa",
        "Xi",
        "Ya",
        "Zu",
      )

    private val TAILS =
      listOf(
        "bara",
        "chi",
        "dele",
        "eze",
        "fani",
        "gwe",
        "hara",
        "ije",
        "jola",
        "kemi",
        "lani",
        "mide",
        "nka",
        "ola",
        "pemi",
        "quri",
        "rina",
        "sola",
        "tunde",
        "uche",
        "vela",
        "wale",
        "xola",
        "yemi",
        "zora",
        "ansa",
      )

    /** 676 distinct values; index `n` is stable for a given `n`. */
    fun composedName(index: Int): String =
      STEMS[(index / TAILS.size) % STEMS.size] + TAILS[index % TAILS.size]

    val NAME_COMBINATIONS = STEMS.size * TAILS.size
  }
}
