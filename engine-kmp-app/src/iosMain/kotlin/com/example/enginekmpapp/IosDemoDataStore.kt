/*
 * Copyright 2026 Google LLC
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

package com.example.enginekmpapp

import com.google.android.fhir.sync.createDataStore
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

private const val DEMO_DATASTORE_FILE = "engine_kmp_demo.preferences_pb"

/** Shared [DemoDataStore] instance — used by both foreground sync and background sync. */
internal val sharedDemoDataStore: DemoDataStore by lazy { createIosDemoDataStore() }

@OptIn(ExperimentalForeignApi::class)
internal fun createIosDemoDataStore(): DemoDataStore {
  val dataStore = createDataStore {
    val docDir =
      NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
      )
    requireNotNull(docDir).path + "/$DEMO_DATASTORE_FILE"
  }
  return DemoDataStore(dataStore)
}
