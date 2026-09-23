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

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.engine.search.NumberClientParam
import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.StringFilterModifier
import dev.ohs.fhir.engine.search.TokenClientParam
import dev.ohs.fhir.engine.search.include
import dev.ohs.fhir.engine.search.revInclude
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.RiskAssessment
import dev.ohs.fhir.model.r4.SearchParameter.SearchComparator
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown

/**
 * What a search costs through the engine rather than through SQLite alone.
 *
 * The index sweeps stop at the row count: they step the result set and never read a payload, and
 * every row they seed holds `{}`. A real search selects `serializedResource` and deserializes one
 * resource per match, so its cost is the query plus a parse per row. `ResourceSerializerBenchmark`
 * puts that parse in the low microseconds, which at a page of results is the larger half. Nothing
 * else here measures the two together.
 *
 * One filter of each supported kind, so a regression in any one of them shows up against the
 * others. `near` is absent because the engine has no position filter, and date is left to
 * [DateIndexShapeBenchmark], which sweeps it properly.
 *
 * [includeSearch] and [revIncludeSearch] are the shapes no other benchmark touches at all, and they
 * are not the same cost. `_include` joins on `re.resourceType||'/'||re.resourceId =
 * rie.index_value`, and an expression on the indexed side cannot be a seek, so neither side of that
 * join uses an index and the work is the product of two tables rather than the size of the result.
 * `_revinclude` binds the same strings from Kotlin and seeks both sides. `SearchQueryPlanTest` pins
 * both plans; this is what the difference costs.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class SearchExecutionBenchmark {

  @Param("1000", "5000") var rows: Int = 0

  private lateinit var database: EngineBenchmarkDatabase

  private lateinit var stringPrefix: Search
  private lateinit var stringExact: Search
  private lateinit var stringContains: Search
  private lateinit var token: Search
  private lateinit var number: Search
  private lateinit var reference: Search
  private lateinit var include: Search
  private lateinit var revInclude: Search
  private lateinit var firstPage: Search
  private lateinit var counted: Search

  @Setup
  fun setUp() {
    val subjects = rows / SUBJECT_SHARE
    val riskRows = rows / RISK_SHARE
    database = EngineBenchmarkDatabase.create("search-$rows")
    database.seedPatients(rows)
    database.seedObservations(rows, subjects)
    database.seedRiskAssessments(riskRows)

    val namePrefix = IndexBenchmarkDatabase.prefixFor(IndexBenchmarkDatabase.PROBE_ROW)
    val family = EngineBenchmarkDatabase.familyName(IndexBenchmarkDatabase.PROBE_ROW)
    val code = EngineBenchmarkDatabase.CODES[IndexBenchmarkDatabase.PROBE_ROW % CODE_COUNT]

    stringPrefix = patientSearch { filter(StringClientParam(FAMILY), { value = namePrefix }) }
    stringExact = patientSearch {
      filter(
        StringClientParam(FAMILY),
        {
          value = family
          modifier = StringFilterModifier.MATCHES_EXACTLY
        },
      )
    }
    stringContains = patientSearch {
      filter(
        StringClientParam(FAMILY),
        {
          value = family
          modifier = StringFilterModifier.CONTAINS
        },
      )
    }
    token = observationSearch { filter(TokenClientParam(CODE), { value = of(code) }) }
    number =
      Search(type = ResourceType.RiskAssessment).apply {
        filter(
          NumberClientParam(PROBABILITY),
          {
            value = BigDecimal.fromInt(RISK_THRESHOLD)
            prefix = SearchComparator.Gt
          },
        )
      }
    reference = observationSearch {
      filter(
        ReferenceClientParam(SUBJECT),
        { value = "Patient/${EngineBenchmarkDatabase.patientId(0)}" },
      )
    }
    include = observationSearch {
      filter(TokenClientParam(CODE), { value = of(code) })
      include<Patient>(ReferenceClientParam(SUBJECT))
    }
    revInclude = patientSearch {
      filter(StringClientParam(FAMILY), { value = namePrefix })
      revInclude<Observation>(ReferenceClientParam(SUBJECT))
    }
    firstPage = Search(type = ResourceType.Patient, count = PAGE, from = 0)
    counted = patientSearch { filter(StringClientParam(FAMILY), { value = namePrefix }) }

    assertSelectivity(
      database.search<Patient>(stringPrefix).size,
      rows,
      1.0 / IndexBenchmarkDatabase.PREFIX_COMBINATIONS,
      "stringPrefix",
    )
    // One name, so one patient. A miss here means the seeded names and the queried one drifted
    // apart and the arm would be timing an empty result.
    check(database.search<Patient>(stringExact).size == 1) {
      "the exact search matched ${database.search<Patient>(stringExact).size} patients, expected 1"
    }
    // The substring is one family name, which is also a prefix of every longer one sharing it, so
    // the count varies with the corpus. That it matches at all is what matters.
    check(database.search<Patient>(stringContains).isNotEmpty()) {
      "the contains search matched nothing, so it is timing a scan over no result"
    }
    assertSelectivity(
      database.search<Observation>(token).size,
      rows,
      1.0 / CODE_COUNT,
      "token",
    )
    // Probabilities are integers spread evenly over 0..99, so above 90 is nine of every hundred.
    assertSelectivity(
      database.search<RiskAssessment>(number).size,
      riskRows,
      (EngineBenchmarkDatabase.MAX_RISK - RISK_THRESHOLD - 1.0) / EngineBenchmarkDatabase.MAX_RISK,
      "number",
    )
    check(database.search<Observation>(reference).size == SUBJECT_SHARE) {
      "one patient should carry $SUBJECT_SHARE observations, found " +
        database.search<Observation>(reference).size
    }
    // An include that returns nothing is the same measurement as the filter without it.
    check(database.search<Observation>(include).any { !it.included.isNullOrEmpty() }) {
      "no result carried an included patient, so includeSearch measures the plain token filter"
    }
    check(database.search<Patient>(revInclude).any { !it.revIncluded.isNullOrEmpty() }) {
      "no result carried a revIncluded observation, so revIncludeSearch measures the plain filter"
    }
    check(database.search<Patient>(firstPage).size == PAGE) {
      "a first page returned ${database.search<Patient>(firstPage).size} patients, expected $PAGE"
    }
  }

  @TearDown fun tearDown() = database.close()

  /** A 1/676 slice, deserialized. The floor the other filters are read against. */
  @Benchmark fun stringPrefixSearch(): Int = database.search<Patient>(stringPrefix).size

  /** One row by an indexed equality, the cheapest shape the string index can answer. */
  @Benchmark fun stringExactSearch(): Int = database.search<Patient>(stringExact).size

  /** `:contains` cannot use the index at all; `SearchQueryPlanTest` pins that. This is its cost. */
  @Benchmark fun stringContainsSearch(): Int = database.search<Patient>(stringContains).size

  /** The most common filter in practice, and the only index that is already covering. */
  @Benchmark fun tokenSearch(): Int = database.search<Observation>(token).size

  /** The only number index the R4 fixtures can produce, over a range rather than an equality. */
  @Benchmark fun numberSearch(): Int = database.search<RiskAssessment>(number).size

  /** The lookup that chained, `has` and `revInclude` searches are all built out of. */
  @Benchmark fun referenceSearch(): Int = database.search<Observation>(reference).size

  /** A second query for the referenced patients, then a grouping pass per matched observation. */
  @Benchmark fun includeSearch(): Int = database.search<Observation>(include).size

  /** The same in reverse: every observation pointing at a matched patient. */
  @Benchmark fun revIncludeSearch(): Int = database.search<Patient>(revInclude).size

  /** Fifty resources with no filter, which is what an unqualified list screen asks for. */
  @Benchmark fun firstPageSearch(): Int = database.search<Patient>(firstPage).size

  /** The same filter counted rather than fetched, which reads no payload and parses nothing. */
  @Benchmark fun countMatching(): Long = database.count(counted)

  private fun patientSearch(init: Search.() -> Unit): Search =
    Search(type = ResourceType.Patient).apply(init)

  private fun observationSearch(init: Search.() -> Unit): Search =
    Search(type = ResourceType.Observation).apply(init)

  private companion object {
    const val FAMILY = "family"
    const val CODE = "code"
    const val SUBJECT = "subject"
    const val PROBABILITY = "probability"

    /** Patients per referenced subject, so a reference lookup matches this many observations. */
    const val SUBJECT_SHARE = 4

    /** Risk assessments are seeded at a quarter of the corpus; they index nothing else needs. */
    const val RISK_SHARE = 4

    /** Above this, which is nine of every hundred seeded probabilities. */
    const val RISK_THRESHOLD = 90

    const val PAGE = 50
    val CODE_COUNT = EngineBenchmarkDatabase.CODES.size
  }
}
