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

import dev.ohs.fhir.engine.search.Order
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.SearchQuery
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.getQuery
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
 * What sorting costs, and whether paging escapes it.
 *
 * No index backs a sorted search. `Search.sort` compiles to a LEFT JOIN onto the index table, a
 * GROUP BY to collapse resources with several indexed values, and an ORDER BY over the result;
 * `SearchQueryPlanTest` shows SQLite answering it with a temporary B-tree for each. The join itself
 * does use the `(resourceUuid, index_name, index_value)` index as a covering one, so what is
 * measured here is the grouping and ordering rather than the lookup.
 *
 * The pair that decides how a client should page is [sortedFirstPage] against [unsortedFirstPage].
 * A LIMIT can stop early only once rows arrive in order, and here they do not, so a sorted page is
 * expected to build the whole ordering before returning its fifty rows. If it does, paging through
 * a sorted list pays that cost per page.
 *
 * The filter arm narrows to a 1/676 slice first, which is the case where sorting should be nearly
 * free: there is almost nothing left to order.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class SortBenchmark {

  @Param("1000", "10000", "50000") var rows: Int = 0

  private lateinit var database: IndexBenchmarkDatabase
  private lateinit var unsorted: SearchQuery
  private lateinit var sorted: SearchQuery
  private lateinit var unsortedFirstPage: SearchQuery
  private lateinit var sortedFirstPage: SearchQuery
  private lateinit var filteredUnsorted: SearchQuery
  private lateinit var filteredSorted: SearchQuery

  @Setup
  fun setUp() {
    database = IndexBenchmarkDatabase.create("sort-$rows")
    database.seed(rows)

    unsorted = search()
    sorted = search(sort = true)
    unsortedFirstPage = search(page = true)
    sortedFirstPage = search(sort = true, page = true)
    filteredUnsorted = search(filterPrefix = probePrefix())
    filteredSorted = search(filterPrefix = probePrefix(), sort = true)

    // Sorting must not change which resources come back, only their order. If it ever does, the
    // sorted and unsorted arms are answering different questions and their difference is not the
    // cost of sorting.
    val unsortedCount = database.count(unsorted)
    check(unsortedCount == rows && database.count(sorted) == rows) {
      "sorted and unsorted searches disagree at $rows rows: $unsortedCount against " +
        database.count(sorted)
    }
    check(database.count(sortedFirstPage) == PAGE) {
      "a sorted first page returned ${database.count(sortedFirstPage)} rows, expected $PAGE"
    }
    assertSelectivity(database.count(filteredSorted), rows, 1.0 / PREFIX_COMBINATIONS, "filtered")
  }

  @TearDown fun tearDown() = database.close()

  /**
   * Every resource of the type, in storage order. The floor the sorted arms are measured against.
   */
  @Benchmark fun unsortedAll(): Int = database.count(unsorted)

  /** The same resources ordered by a string parameter. */
  @Benchmark fun sortedAll(): Int = database.count(sorted)

  /** Fifty rows with no ordering, which a LIMIT can satisfy without reading the rest. */
  @Benchmark fun unsortedFirstPage(): Int = database.count(unsortedFirstPage)

  /**
   * Fifty rows in order. The gap against [unsortedFirstPage] is what paging a sorted list costs.
   */
  @Benchmark fun sortedFirstPage(): Int = database.count(sortedFirstPage)

  /** A 1/676 slice, unordered. */
  @Benchmark fun filteredUnsorted(): Int = database.count(filteredUnsorted)

  /** The same slice ordered, where there is little left to sort. */
  @Benchmark fun filteredSorted(): Int = database.count(filteredSorted)

  private fun search(
    filterPrefix: String? = null,
    sort: Boolean = false,
    page: Boolean = false,
  ): SearchQuery =
    Search(
        type = ResourceType.Patient,
        count = if (page) PAGE else null,
        from = if (page) 0 else null,
      )
      .apply {
        if (filterPrefix != null) filter(StringClientParam(SORT_PARAM), { value = filterPrefix })
        if (sort) sort(StringClientParam(SORT_PARAM), Order.ASCENDING)
      }
      .getQuery()

  private fun probePrefix() = IndexBenchmarkDatabase.prefixFor(PROBE_ROW)

  private companion object {
    /** The parameter `IndexBenchmarkDatabase.seed` writes string index rows for. */
    const val SORT_PARAM = "given"

    /** A plausible page of search results. */
    const val PAGE = 50

    const val PROBE_ROW = 42
    const val PREFIX_COMBINATIONS = 26 * 26
  }
}
