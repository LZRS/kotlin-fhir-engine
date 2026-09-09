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

import dev.ohs.fhir.engine.benchmark.DatasetManifest
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * Synthea bulk data: one `.ndjson` per resource type, one resource per line.
 *
 * Built by [load] rather than a constructor, because the corpus is far larger than the parsed
 * resources it yields: `Patient.ndjson` alone runs to hundreds of megabytes at benchmark
 * populations, and holding a file as a string before parsing it costs more than the dataset does.
 *
 * Parsed leniently. Synthea emits US Core profiles and extensions the model does not carry, and a
 * strict parse would reject most of the corpus; unknown keys are dropped instead. [parseFailures]
 * records the lines that still failed so a half-loaded dataset is visible rather than silent.
 */
class NdjsonDataset
private constructor(
  private val orderedFiles: List<String>,
  private val lines: (String) -> Flow<String>,
  private val counts: Map<String, Int>,
  override val patientIds: List<String>,
  override val sampleObservationCode: String,
  override val sampleOrganizationId: String,
  override val sampleFamilyName: String,
  override val sampleGivenName: String,
  val parseFailures: List<String>,
  private val seed: Int,
  private val fingerprint: String,
) : Dataset {

  override val population: Int = patientIds.size

  override val resourceCount: Int = counts.values.sum()

  /**
   * Re-parses the corpus on every call.
   *
   * Deliberate: holding it is what a phone cannot afford, and the files are on local storage, so a
   * second pass costs time rather than the run. Referenced types come first, in the same order
   * [load] walked, so the fingerprint taken there describes exactly this sequence.
   */
  override fun resources(): Flow<Resource> = flow {
    for (name in orderedFiles) {
      lines(name).collect { line ->
        if (line.isBlank()) return@collect
        val resource =
          try {
            JSON.decodeFromString<Resource>(line)
          } catch (e: Exception) {
            null
          }
        if (resource != null) emit(resource)
      }
    }
  }

  override fun manifest() =
    DatasetManifest(
      kind = "synthea",
      population = population,
      seed = seed,
      resourceCounts = counts,
      fingerprint = fingerprint,
    )

  companion object {
    private val JSON = Json {
      explicitNulls = false
      encodeDefaults = false
      ignoreUnknownKeys = true
    }

    /**
     * Reads the corpus once to learn what is in it, keeping counts, ids and a fingerprint but none
     * of the resources.
     *
     * [lines] hands back one line per NDJSON record, which is the whole point of the format: peak
     * memory here is a single parsed resource, not the corpus. Passing it in rather than calling
     * the platform reader keeps this package free of platform code.
     */
    suspend fun load(
      fileNames: List<String>,
      seed: Int,
      requestedPopulation: Int,
      lines: (String) -> Flow<String>,
      cache: DatasetCache? = null,
      cacheKey: String? = null,
    ): NdjsonDataset {
      val ordered = inLoadOrder(fileNames)
      cached(cache, cacheKey)?.let { metadata ->
        return NdjsonDataset(
          orderedFiles = ordered,
          lines = lines,
          counts = metadata.counts,
          patientIds = metadata.patientIds,
          sampleObservationCode = metadata.sampleObservationCode,
          sampleFamilyName = metadata.sampleFamilyName,
          sampleGivenName = metadata.sampleGivenName,
          sampleOrganizationId = metadata.sampleOrganizationId,
          // A cached scan found no failures worth replaying; the run that wrote it reported them.
          parseFailures = emptyList(),
          seed = seed,
          fingerprint = metadata.fingerprint,
        )
      }
      val counts = mutableMapOf<String, Int>()
      val patientIds = mutableListOf<String>()
      val observationCodes = mutableMapOf<String, Int>()
      var organizationId = ""
      var familyName = ""
      var givenName = ""
      val parseFailures = mutableListOf<String>()
      var hash = 17L

      for (name in ordered) {
        val type = name.substringBefore(".")
        var count = 0
        lines(name).collect { line ->
          if (line.isBlank()) return@collect
          val resource =
            try {
              JSON.decodeFromString<Resource>(line)
            } catch (e: Exception) {
              parseFailures += "$name: ${e::class.simpleName}: ${e.message?.take(160)}"
              return@collect
            }
          count++
          hash = hash * 31 + resource.id.hashCode()
          hash = hash * 31 + resource::class.simpleName.hashCode()
          when (resource) {
            is Patient -> {
              resource.id?.let { patientIds += it }
              // Synthea surnames are varied enough that one whole name is a selective search; the
              // first patient's is as good as any, and costs nothing extra to capture here.
              if (familyName.isEmpty()) {
                resource.name.firstOrNull()?.let { name ->
                  familyName = name.family?.value.orEmpty()
                  givenName = name.given.firstOrNull()?.value.orEmpty()
                }
              }
            }
            is Organization -> if (organizationId.isEmpty()) organizationId = resource.id ?: ""
            is Observation ->
              observationCode(resource)?.let {
                observationCodes[it] = (observationCodes[it] ?: 0) + 1
              }
            else -> Unit
          }
        }
        // Accumulated, not assigned: packaging splits the big types across numbered files, so
        // one type arrives as several.
        counts[type] = (counts[type] ?: 0) + count
      }

      hash = hash * 31 + requestedPopulation
      hash = hash * 31 + seed
      val fingerprint = hash.toULong().toString(16).padStart(16, '0')
      // Synthea's mix varies with the seed, hence discovering the commonest code rather than
      // hard-coding one that might match no row at all.
      val code = observationCodes.maxByOrNull { it.value }?.key ?: "8867-4"
      if (cache != null && cacheKey != null) {
        cache.write(
          cacheKey,
          JSON.encodeToString(
            DatasetMetadata(
              counts = counts,
              patientIds = patientIds,
              sampleObservationCode = code,
              sampleOrganizationId = organizationId,
              sampleFamilyName = familyName,
              sampleGivenName = givenName,
              fingerprint = fingerprint,
            ),
          ),
        )
      }
      return NdjsonDataset(
        orderedFiles = ordered,
        lines = lines,
        counts = counts,
        patientIds = patientIds,
        sampleObservationCode = code,
        sampleOrganizationId = organizationId,
        sampleFamilyName = familyName,
        sampleGivenName = givenName,
        parseFailures = parseFailures,
        seed = seed,
        fingerprint = fingerprint,
      )
    }

    /**
     * A previous scan, or null to scan again.
     *
     * An unreadable entry is treated as a miss rather than a failure: a stale or truncated cache
     * must never be the reason a benchmark run stops.
     */
    private suspend fun cached(cache: DatasetCache?, key: String?): DatasetMetadata? {
      if (cache == null || key == null) return null
      val stored = cache.read(key) ?: return null
      return try {
        JSON.decodeFromString<DatasetMetadata>(stored)
      } catch (e: Exception) {
        null
      }
    }

    /** Referenced types first, so references resolve as the corpus is inserted. */
    private fun inLoadOrder(fileNames: List<String>): List<String> =
      fileNames
        .filter { it.substringBefore(".") in INCLUDED_TYPES }
        .sortedBy {
          val index = LOAD_ORDER.indexOf(it.substringBefore("."))
          if (index < 0) LOAD_ORDER.size else index
        }

    private fun observationCode(observation: Observation): String? =
      observation.code.coding.firstOrNull()?.code?.value

    /**
     * The types the workloads touch. Synthea also emits Claim, ExplanationOfBenefit and
     * DocumentReference, which together dwarf everything else and none of the queries look at.
     */
    val INCLUDED_TYPES =
      setOf(
        "Organization",
        "Practitioner",
        "Patient",
        "Encounter",
        "Condition",
        "Observation",
        "Procedure",
        "Immunization",
        "AllergyIntolerance",
        "MedicationRequest",
        "CarePlan",
      )

    private val LOAD_ORDER = listOf("Organization", "Practitioner", "Patient", "Encounter")
  }
}
