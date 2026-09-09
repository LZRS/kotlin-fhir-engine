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
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.Decimal as FhirDecimal
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Quantity
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** How much clinical data to build for each patient in the corpus. */
data class ClinicalMix(val observations: Int = 8, val conditions: Int = 2) {
  val perPatient: Int
    get() = observations + conditions

  companion object {
    /**
     * Reads the compact `<observations>x<conditions>` form, e.g. `8x2`. `off` means generate
     * nothing, which is how a run stays comparable to a harness that measured the corpus alone.
     * Malformed input fails rather than guessing: a typo silently becoming the default would change
     * what a whole run measured.
     */
    fun parse(value: String?): ClinicalMix {
      val trimmed = value?.trim().orEmpty()
      if (trimmed.isEmpty()) return ClinicalMix()
      if (trimmed.equals("off", ignoreCase = true)) return ClinicalMix(0, 0)
      val parts = trimmed.lowercase().split("x")
      require(parts.size == 2) {
        "benchmark.mix must be <observations>x<conditions> or off: $trimmed"
      }
      val observations = requireNotNull(parts[0].toIntOrNull()) { "Not a number: ${parts[0]}" }
      val conditions = requireNotNull(parts[1].toIntOrNull()) { "Not a number: ${parts[1]}" }
      return ClinicalMix(observations, conditions)
    }
  }
}

/**
 * A Synthea corpus plus the clinical resources it does not carry.
 *
 * Synthea only emits observations and conditions when its full module set runs, and that multiplies
 * the corpus by roughly forty — 50,000 patients would export around 5 GB and take over half a day
 * to insert on a benchmark tablet. Generating instead keeps the corpus at its packaged size and
 * makes the per-patient volume a number this class is given rather than one Synthea decides.
 *
 * The patients are real: every generated resource references an id that came out of the corpus, so
 * the joins and reverse-includes the search workloads measure resolve against real rows.
 */
class AugmentedDataset(
  private val base: Dataset,
  private val mix: ClinicalMix = ClinicalMix(),
  private val seed: Int = 0,
) : Dataset {

  override val population: Int = base.population

  override val resourceCount: Int = base.resourceCount + base.patientIds.size * mix.perPatient

  override val patientIds: List<String> = base.patientIds

  override val sampleOrganizationId: String = base.sampleOrganizationId

  /**
   * The code the generator uses most. The corpus's own answer is meaningless here: it has no
   * observations, so the token query would be built from a code nothing carries.
   */
  override val sampleObservationCode: String = OBSERVATION_CODES.first()

  /** Names come from the corpus; this wrapper adds observations and conditions, not patients. */
  override val sampleFamilyName: String = base.sampleFamilyName

  override val sampleGivenName: String = base.sampleGivenName

  /** Corpus first, so patients exist before anything references them. */
  override fun resources(): Flow<Resource> = flow {
    base.resources().collect { emit(it) }
    // Generated per patient and emitted immediately: the point of streaming is that no more than
    // one resource is resident, and a 50,000-patient mix would otherwise be half a million objects.
    for ((index, patientId) in base.patientIds.withIndex()) {
      val random = Random(seed * 31 + index)
      repeat(mix.observations) { slot -> emit(observation(patientId, index, slot, random)) }
      repeat(mix.conditions) { slot -> emit(condition(patientId, index, slot)) }
    }
  }

  override fun manifest(): DatasetManifest {
    val counts = base.manifest().resourceCounts.toMutableMap()
    val patients = base.patientIds.size
    counts["Observation"] = (counts["Observation"] ?: 0) + patients * mix.observations
    counts["Condition"] = (counts["Condition"] ?: 0) + patients * mix.conditions
    return base
      .manifest()
      .copy(
        kind = "${base.manifest().kind}+generated",
        resourceCounts = counts,
        // Distinct from the corpus's own: the same files with a different mix are a different
        // dataset, and two reports carrying one fingerprint must have measured one thing.
        fingerprint = "${base.manifest().fingerprint}-g${mix.observations}x${mix.conditions}s$seed",
      )
  }

  private fun observation(patientId: String, index: Int, slot: Int, random: Random) =
    Observation(
      id = "bench-observation-$index-$slot",
      status = Enumeration(value = Observation.ObservationStatus.Final),
      code =
        CodeableConcept(
          coding =
            listOf(
              Coding(
                system = Uri(value = LOINC_SYSTEM),
                code = Code(value = OBSERVATION_CODES[(index + slot) % OBSERVATION_CODES.size]),
              ),
            ),
        ),
      subject = Reference(reference = FhirString(value = "Patient/$patientId")),
      // A spread of values, so the quantity range query matches a share of rows rather than all
      // of them or none.
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

  private fun condition(patientId: String, index: Int, slot: Int) =
    Condition(
      id = "bench-condition-$index-$slot",
      code =
        CodeableConcept(
          coding =
            listOf(
              Coding(
                system = Uri(value = SNOMED_SYSTEM),
                code = Code(value = CONDITION_CODES[(index + slot) % CONDITION_CODES.size]),
              ),
            ),
        ),
      subject = Reference(reference = FhirString(value = "Patient/$patientId")),
    )

  companion object {
    /** Shared with [SyntheticDataset] so a query means the same thing whichever dataset ran. */
    private val OBSERVATION_CODES = SyntheticDataset.OBSERVATION_CODES

    private val CONDITION_CODES = SyntheticDataset.CONDITION_CODES

    private const val LOINC_SYSTEM = SyntheticDataset.LOINC_SYSTEM
    private const val SNOMED_SYSTEM = SyntheticDataset.SNOMED_SYSTEM
    private const val UCUM_SYSTEM = SyntheticDataset.UCUM_SYSTEM
  }
}
