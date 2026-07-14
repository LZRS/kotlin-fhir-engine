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
package dev.ohs.fhirdemo.data

import dev.ohs.fhir.FhirEngine
import dev.ohs.fhir.model.r4.terminologies.ResourceType

/**
 * [LastUpdatedStore] backed by [FhirEngine.getLastUpdated] instead of a separate preferences store.
 * On web, resource data lives in OPFS while `localStorage`-backed preferences can persist
 * independently of it (e.g. a fresh private-browsing tab), so a store kept apart from the database
 * can end up pointing past data the tab doesn't actually have. Deriving the watermark from the
 * database itself means it always reflects what's actually persisted there.
 */
class EngineBackedLastUpdatedStore(private val fhirEngine: FhirEngine) : LastUpdatedStore {
  override suspend fun getLastUpdateTimestamp(resourceType: ResourceType): String? =
    fhirEngine.getLastUpdated(resourceType)?.toString()

  // No-op: the watermark is derived from resources already persisted in the database, so there's
  // nothing separate to save.
  override suspend fun saveLastUpdatedTimestamp(resourceType: ResourceType, timestamp: String) {}
}
