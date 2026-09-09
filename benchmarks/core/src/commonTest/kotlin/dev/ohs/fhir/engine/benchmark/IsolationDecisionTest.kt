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
package dev.ohs.fhir.engine.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The isolation a workload asks for is not always the one it gets. Every downgrade has to be
 * reported, because a number measured under weaker isolation than it claims is worse than no number
 * at all.
 */
class IsolationDecisionTest {

  @Test
  fun `a read-only workload keeps its warm cache by default`() {
    val (isolation, note) = effectiveIsolation(Isolation.NONE, coldCache = false, canReopen = true)

    assertEquals(Isolation.NONE, isolation)
    assertNull(note)
  }

  @Test
  fun `cold cache promotes a read-only workload to a reopened database`() {
    val (isolation, note) = effectiveIsolation(Isolation.NONE, coldCache = true, canReopen = true)

    assertEquals(Isolation.COLD_CACHE, isolation)
    assertNull(note)
  }

  @Test
  fun `cold cache is refused where the platform cannot reopen the database`() {
    val (isolation, note) = effectiveIsolation(Isolation.NONE, coldCache = true, canReopen = false)

    assertEquals(Isolation.NONE, isolation)
    assertNotNull(note)
  }

  @Test
  fun `cold cache leaves a table-clearing workload alone`() {
    // CLEAR_TABLES exists to keep the page cache warm on purpose. Promoting it would silently
    // change what the workload measures.
    val (isolation, note) =
      effectiveIsolation(Isolation.CLEAR_TABLES, coldCache = true, canReopen = true)

    assertEquals(Isolation.CLEAR_TABLES, isolation)
    assertNull(note)
  }

  @Test
  fun `cold cache leaves a fresh-database workload alone`() {
    val (isolation, note) =
      effectiveIsolation(Isolation.FRESH_DATABASE, coldCache = true, canReopen = true)

    assertEquals(Isolation.FRESH_DATABASE, isolation)
    assertNull(note)
  }

  @Test
  fun `a fresh database degrades to clearing tables where it cannot be reopened`() {
    val (isolation, note) =
      effectiveIsolation(Isolation.FRESH_DATABASE, coldCache = false, canReopen = false)

    assertEquals(Isolation.CLEAR_TABLES, isolation)
    assertNotNull(note)
  }
}
