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
package dev.ohs.fhir.engine.db.impl

import androidx.room3.useReaderConnection
import androidx.sqlite.async.step
import dev.ohs.fhir.engine.testPlatformContext
import dev.ohs.fhir.engine.testStorageDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The journal mode a file-backed database opens in.
 *
 * The engine never sets one, so this is Room's default for the driver in use. It matters more than
 * a default usually does: under a rollback journal a writer locks the database for the length of
 * its transaction, and a search running while a sync writes waits for it.
 * `ConcurrentAccessBenchmark` measures that at roughly 20x on a 20,000-patient corpus.
 *
 * Pinned here so an upgrade that changes the default fails a test rather than showing up as a
 * report of stalled reads.
 */
class JournalModeTest {

  private var database: ResourceDatabase? = null

  @AfterTest
  fun tearDown() {
    database?.close()
  }

  @Test
  fun `a file-backed database opens in WAL`() = runTest {
    val opened =
      getDatabaseBuilder(
          platformContext = testPlatformContext(),
          storageDirectory = testStorageDirectory(),
          inMemory = false,
        )
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
        .also { database = it }

    val mode =
      opened.useReaderConnection { transactor ->
        transactor.usePrepared("PRAGMA journal_mode") { statement ->
          if (statement.step()) statement.getText(0) else ""
        }
      }

    assertEquals("wal", mode.lowercase(), "reads stall behind a writer in any other mode")
  }
}
