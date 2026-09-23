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
package dev.ohs.fhir.engine.microbenchmark

import dev.ohs.fhir.engine.SearchResult
import dev.ohs.fhir.engine.db.impl.DatabaseImpl
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.resourceTypeEnum
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.count
import dev.ohs.fhir.engine.search.execute
import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.Decimal
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDecimal
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Identifier
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Quantity
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.RiskAssessment
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/**
 * A real [DatabaseImpl], written through the engine's own paths, for measuring what a search or a
 * write costs end to end.
 *
 * The counterpart to [IndexBenchmarkDatabase], which writes index rows by hand so that only SQLite
 * is in the measurement. Everything here pays for FHIRPath indexing, serialization, Room and the
 * local-change ledger, because the question is what the engine costs rather than what an index
 * costs. Seeding is correspondingly expensive — roughly 300 us a resource — so these corpora are
 * smaller than the index sweeps'.
 *
 * Value distributions are scale-invariant the same way: names cycle through all 676 two-letter
 * prefixes, observation codes through [CODES], and probabilities spread evenly across [MAX_RISK],
 * so a fixed query selects the same fraction of the corpus at every size.
 */
internal class EngineBenchmarkDatabase(
  /** Exposed so [DatabaseOpenBenchmark] can close a database and open the same files again. */
  val directory: File,
) {

  val database =
    DatabaseImpl(
      platformContext = Unit,
      resourceIndexer = ResourceIndexer(SearchParamDefinitionsProviderImpl()),
      storageDirectory = directory.absolutePath,
      inMemory = false,
    )

  /**
   * Writes [rows] patients as remote resources.
   *
   * Remote rather than local: an insert through [DatabaseImpl.insert] also records a local change,
   * which is a write-path cost and not something a search should pay for in its setup.
   * `EngineCreateBenchmark` measures that path instead.
   */
  fun seedPatients(rows: Int) = runBlocking {
    (0 until rows).chunked(SEED_BATCH).forEach { chunk ->
      database.insertRemote(*chunk.map(::patient).toTypedArray())
    }
  }

  /** Writes [rows] observations, spread over [subjects] patients so each has several. */
  fun seedObservations(rows: Int, subjects: Int) = runBlocking {
    (0 until rows).chunked(SEED_BATCH).forEach { chunk ->
      database.insertRemote(*chunk.map { observation(it, subjects) }.toTypedArray())
    }
  }

  /** Writes the organizations every seeded patient points at, so an `_include` resolves them. */
  fun seedOrganizations() = runBlocking {
    database.insertRemote(*(0 until ORGANIZATIONS).map(::organization).toTypedArray())
  }

  /** Writes [rows] risk assessments, the only fixture carrying a number index. */
  fun seedRiskAssessments(rows: Int) = runBlocking {
    (0 until rows).chunked(SEED_BATCH).forEach { chunk ->
      database.insertRemote(*chunk.map { riskAssessment(it, rows) }.toTypedArray())
    }
  }

  /** Writes [resources] as remote ones, which indexes them without recording a local change. */
  fun seedGiven(resources: List<Resource>) = runBlocking {
    resources.chunked(SEED_BATCH).forEach { chunk -> database.insertRemote(*chunk.toTypedArray()) }
  }

  /**
   * Runs [search] the way `FhirEngine.search` does, resources deserialized and includes fetched.
   */
  fun <R : Resource> search(search: Search): List<SearchResult<R>> = runBlocking {
    search.execute(database)
  }

  /** The `COUNT(*)` path, which returns a number rather than any resource. */
  fun count(search: Search): Long = runBlocking { search.count(database) }

  /** How many resources of [type] the corpus holds, for a benchmark asserting its own fixture. */
  fun countOf(type: ResourceType): Long = runBlocking { Search(type).count(database) }

  fun insert(resources: List<Resource>) = runBlocking { database.insert(*resources.toTypedArray()) }

  fun update(resources: List<Resource>) = runBlocking { database.update(*resources.toTypedArray()) }

  fun delete(resources: List<Resource>) = runBlocking {
    resources.forEach { database.delete(it.resourceTypeEnum, it.id.orEmpty()) }
  }

  /** The download path: many resources in one transaction, with no local change recorded. */
  fun importRemote(resources: List<Resource>) = runBlocking {
    database.insertSyncedResources(resources)
  }

  /**
   * Removes the local changes a write benchmark's invocation recorded.
   *
   * A create followed by a delete leaves two rows in the ledger rather than none, so without this
   * the table grows for the whole run and every later invocation writes into a bigger one.
   */
  fun discardChanges(resources: List<Resource>) = runBlocking { database.deleteUpdates(resources) }

  fun localChangeCount(): Int = runBlocking { database.getLocalChangesCount() }

  /** Everything the upload pipeline reads before it can generate a single request. */
  fun allLocalChanges() = runBlocking { database.getAllLocalChanges() }

  fun localChangeReferences(ids: List<Long>) = runBlocking {
    database.getLocalChangeResourceReferences(ids)
  }

  fun clear() = runBlocking { database.clearDatabase() }

  fun close() {
    database.close()
    directory.deleteRecursively()
  }

  companion object {
    /** Resources per transaction while seeding. Bounds memory without paying a commit per row. */
    const val SEED_BATCH = 500

    private const val LOINC = "http://loinc.org"
    private const val UCUM = "http://unitsofmeasure.org"

    /** Distinct observation codes, so a token search for one of them selects 1/64 at any size. */
    val CODES = (0 until 64).map { "code-$it" }

    /** Risk probabilities spread across this range, whatever the row count. */
    const val MAX_RISK = 100

    fun patientId(row: Int) = "engine-patient-$row"

    fun organizationId(row: Int) = "engine-organization-$row"

    /**
     * Distinct organizations the patients are spread over. Few enough that an `_include` resolves
     * to a small set however large the corpus, which is the shape a real one has.
     */
    const val ORGANIZATIONS = 64

    /** The family name at [row]; its two-letter prefix selects 1/676 of the patients. */
    fun familyName(row: Int) = "${IndexBenchmarkDatabase.prefixFor(row)}family-$row"

    fun patient(row: Int): Patient =
      Patient(
        id = patientId(row),
        active = FhirBoolean(value = true),
        identifier =
          listOf(
            Identifier(
              system = Uri(value = "urn:oid:1.2.3"),
              value = FhirString(value = "mrn-$row"),
            ),
          ),
        name =
          listOf(
            HumanName(
              family = FhirString(value = familyName(row)),
              given = listOf(FhirString(value = "${IndexBenchmarkDatabase.prefixFor(row)}given")),
            ),
          ),
        gender =
          Enumeration(
            value = if (row % 2 == 0) AdministrativeGender.Female else AdministrativeGender.Male,
          ),
        birthDate = Date(value = FhirDate.Date(date = LocalDate(1980, 4, 17))),
        // Two references apiece. A patient carrying none costs `LocalChangeDao` nothing to extract,
        // and an update's reference diff — which walks both the old and the new tree — then
        // measures an empty walk.
        managingOrganization =
          Reference(
            reference = FhirString(value = "Organization/${organizationId(row % ORGANIZATIONS)}"),
          ),
        generalPractitioner =
          listOf(
            Reference(reference = FhirString(value = "Practitioner/engine-practitioner-$row")),
          ),
      )

    fun organization(row: Int): Organization =
      Organization(
        id = organizationId(row),
        active = FhirBoolean(value = true),
        name = FhirString(value = "Organization ${IndexBenchmarkDatabase.prefixFor(row)}"),
      )

    fun observation(row: Int, subjects: Int): Observation =
      Observation(
        id = "engine-observation-$row",
        status = Enumeration(value = Observation.ObservationStatus.Final),
        code =
          CodeableConcept(
            coding =
              listOf(
                Coding(
                  system = Uri(value = LOINC),
                  code = Code(value = CODES[row % CODES.size]),
                ),
              ),
          ),
        subject = Reference(reference = FhirString(value = "Patient/${patientId(row % subjects)}")),
        value =
          Observation.Value.Quantity(
            Quantity(
              value = Decimal(value = FhirDecimal.fromInt(row % MAX_RISK)),
              unit = FhirString(value = "g/dL"),
              system = Uri(value = UCUM),
              code = Code(value = "g/dL"),
            ),
          ),
      )

    fun riskAssessment(row: Int, rows: Int): RiskAssessment =
      RiskAssessment(
        id = "engine-risk-$row",
        // The generator names every status enum after Observation's, whatever the resource.
        status = Enumeration(value = RiskAssessment.ObservationStatus.Final),
        subject = Reference(reference = FhirString(value = "Patient/${patientId(row)}")),
        prediction =
          listOf(
            RiskAssessment.Prediction(
              probability =
                RiskAssessment.Prediction.Probability.Decimal(
                  Decimal(value = FhirDecimal.fromInt(probabilityFor(row, rows))),
                ),
            ),
          ),
      )

    /**
     * The probability at [row]: an integer spread evenly across [MAX_RISK] whatever the row count,
     * so a fixed threshold selects the same fraction of the corpus at every size.
     */
    fun probabilityFor(row: Int, rows: Int): Int = row * MAX_RISK / rows

    /** A fresh database directory per trial, so nothing carries over between combinations. */
    fun create(label: String): EngineBenchmarkDatabase {
      val directory = File.createTempFile("bench-engine-$label-", "")
      directory.delete()
      directory.mkdirs()
      return EngineBenchmarkDatabase(directory)
    }
  }
}
