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
 * Whether making `StringIndexEntity.index_value` NOCASE pays for itself, and at what size.
 *
 * A prefix search compiles to `index_value LIKE ? || '%' COLLATE NOCASE`, which cannot use a BINARY
 * index. Declaring the column NOCASE and binding the pattern whole turns it into a range seek. That
 * combination measured as no change at 1,000 patients; [rows] sweeps whether size changes the
 * answer.
 *
 * The two arms differ in both the index collation and the SQL, because either alone leaves the
 * optimisation off.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class StringIndexCollationBenchmark {

  @Param("1000", "10000", "50000") var rows: Int = 0

  /** `binary` is what the engine ships; `nocase` is the collation plus the bound-pattern SQL. */
  @Param("binary", "nocase") var collation: String = ""

  private lateinit var database: IndexBenchmarkDatabase
  private lateinit var query: SearchQuery

  @Setup
  fun setUp() {
    database = IndexBenchmarkDatabase.create("string-$collation-$rows")
    database.seed(rows)
    database.reindex(
      "StringIndexEntity",
      when (collation) {
        "binary" ->
          listOf(
            "`resourceType`, `index_name`, `index_value`",
            "`resourceUuid`, `index_name`, `index_value`",
          )
        // COLLATE NOCASE is explicit because `index_value` is declared BINARY: an index over it
        // inherits that collation, so without this the "nocase" arm is a second BINARY arm.
        "nocase" ->
          listOf(
            "`resourceType`, `index_name`, `index_value` COLLATE NOCASE",
            "`resourceUuid`, `index_name`, `index_value` COLLATE NOCASE",
          )
        else -> error("Unknown collation: $collation")
      },
    )

    val prefix = IndexBenchmarkDatabase.prefixFor(IndexBenchmarkDatabase.PROBE_ROW)
    query =
      Search(ResourceType.Patient)
        .apply { filter(StringClientParam("given"), { value = prefix }) }
        .getQuery()
    // `nocase` binds the pattern whole, the half of the optimisation that lives in the SQL rather
    // than in the schema.
    if (collation == "nocase") {
      query =
        SearchQuery(
          query.query.replace("index_value LIKE ? || '%' COLLATE NOCASE", "index_value LIKE ?"),
          query.args.dropLast(1) + "$prefix%",
        )
    }

    // The arms must differ before they are worth timing: `binary` must fail to narrow on
    // index_value and `nocase` must succeed.
    val plan = database.planFor(query).joinToString(" | ")
    val narrowsOnValue = "index_value>?" in plan
    check(narrowsOnValue == (collation == "nocase")) {
      "arm '$collation' produced the wrong plan, so the arms measure the same thing: $plan"
    }

    // One of 676 two-letter prefixes, so both arms must land on the same small slice.
    assertSelectivity(
      database.count(query),
      rows,
      1.0 / IndexBenchmarkDatabase.PREFIX_COMBINATIONS,
      collation,
    )
  }

  @TearDown fun tearDown() = database.close()

  @Benchmark fun prefixSearch(): Int = database.count(query)
}
