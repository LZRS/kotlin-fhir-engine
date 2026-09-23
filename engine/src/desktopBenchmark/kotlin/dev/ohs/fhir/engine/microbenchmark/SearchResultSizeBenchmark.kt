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

import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.include
import dev.ohs.fhir.engine.search.revInclude
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Patient
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
 * How a search scales with the number of resources it returns, rather than with the corpus it
 * searches.
 *
 * Every other search benchmark here fixes a filter and lets the corpus decide how many rows come
 * back, which keeps the result sets small. [page] varies the returned count directly: the filter is
 * absent and [results] is the page size, so the query is a constant and the only variable is how
 * many resources are read and parsed.
 *
 * [pageWithInclude] and [pageWithRevInclude] add the second query on the same page. Read each
 * against [page] at the same size: the difference is what resolving references costs, and it is
 * quadratic in the page rather than linear. `Search.execute` groups the resolved resources by
 * rescanning the whole list once per base result, and on the revInclude side it rebuilds the
 * `type/id` key inside that scan. See docs/benchmarking.md.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class SearchResultSizeBenchmark {

  @Param("1", "100", "1000", "10000") var results: Int = 0

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var page: Search
  private lateinit var withInclude: Search
  private lateinit var withRevInclude: Search

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("resultsize-$results")
    database.seedOrganizations()
    database.seedPatients(CORPUS)
    // Observations over every patient the largest page can reach, so a revInclude has something to
    // find whatever the page size.
    database.seedObservations(CORPUS, subjects = CORPUS)

    page = pagedSearch()
    withInclude = pagedSearch().apply { include<Organization>(ReferenceClientParam(ORGANIZATION)) }
    withRevInclude = pagedSearch().apply { revInclude<Observation>(ReferenceClientParam(SUBJECT)) }

    check(database.search<Patient>(page).size == results) {
      "the page returned ${database.search<Patient>(page).size} patients, expected $results"
    }
    // An include or revInclude that resolves nothing costs only its query, so the arms would
    // measure the page and a wasted round trip rather than the work of resolving references.
    check(database.search<Patient>(withInclude).any { !it.included.isNullOrEmpty() }) {
      "no result carried an included organization"
    }
    check(database.search<Patient>(withRevInclude).any { !it.revIncluded.isNullOrEmpty() }) {
      "no result carried a revIncluded observation"
    }
  }

  @TearDown fun tearDown() = database.close()

  /** [results] resources read and parsed, with no filter and no ordering to pay for. */
  @Benchmark fun page(): Int = database.search<Patient>(page).size

  /** The same page, plus the organizations it references. */
  @Benchmark fun pageWithInclude(): Int = database.search<Patient>(withInclude).size

  /** The same page, plus the observations that reference it. */
  @Benchmark fun pageWithRevInclude(): Int = database.search<Patient>(withRevInclude).size

  private fun pagedSearch() = Search(type = ResourceType.Patient, count = results, from = 0)

  private companion object {
    /** Large enough for the biggest page, and the same for every arm so only the page varies. */
    const val CORPUS = 10_000

    const val ORGANIZATION = "organization"
    const val SUBJECT = "subject"
  }
}
